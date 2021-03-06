package org.infinispan.interceptors.distribution;

import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.remote.ClusteredGetCommand;
import org.infinispan.commands.write.DataWriteCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.CacheException;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.interceptors.ClusteringInterceptor;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.remoting.responses.CacheNotFoundResponse;
import org.infinispan.remoting.responses.ClusteredGetResponseValidityFilter;
import org.infinispan.remoting.responses.ExceptionResponse;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.ResponseFilter;
import org.infinispan.remoting.rpc.ResponseMode;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.statetransfer.OutdatedTopologyException;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import java.util.*;

/**
 * Base class for distribution of entries across a cluster.
 *
 * @author Manik Surtani
 * @author Mircea.Markus@jboss.com
 * @author Pete Muir
 * @author Dan Berindei <dan@infinispan.org>
 * @since 4.0
 */
public abstract class BaseDistributionInterceptor extends ClusteringInterceptor {

   protected DistributionManager dm;

   protected ClusteringDependentLogic cdl;

   private static final Log log = LogFactory.getLog(BaseDistributionInterceptor.class);

   @Override
   protected Log getLog() {
      return log;
   }

   @Inject
   public void injectDependencies(DistributionManager distributionManager, ClusteringDependentLogic cdl) {
      this.dm = distributionManager;
      this.cdl = cdl;
   }

   @Override
   protected final InternalCacheEntry retrieveFromRemoteSource(Object key, InvocationContext ctx, boolean acquireRemoteLock, FlagAffectedCommand command, boolean isWrite) throws Exception {
      GlobalTransaction gtx = ctx.isInTxScope() ? ((TxInvocationContext)ctx).getGlobalTransaction() : null;
      ClusteredGetCommand get = cf.buildClusteredGetCommand(key, command.getFlags(), acquireRemoteLock, gtx);
      get.setWrite(isWrite);

      List<Address> targets = new ArrayList<Address>(stateTransferManager.getCacheTopology().getReadConsistentHash().locateOwners(key));
      // if any of the recipients has left the cluster since the command was issued, just don't wait for its response
      targets.retainAll(rpcManager.getTransport().getMembers());
      ResponseFilter filter = new ClusteredGetResponseValidityFilter(targets, rpcManager.getAddress());
      RpcOptions options = rpcManager.getRpcOptionsBuilder(ResponseMode.WAIT_FOR_VALID_RESPONSE, false)
            .responseFilter(filter).build();
      Map<Address, Response> responses = rpcManager.invokeRemotely(targets, get, options);

      if (!responses.isEmpty()) {
         for (Response r : responses.values()) {
            if (r instanceof SuccessfulResponse) {
               
               // The response value might be null.
               SuccessfulResponse response = (SuccessfulResponse)r;
               if( response.getResponseValue() == null )
                  return null;
               
               InternalCacheValue cacheValue = (InternalCacheValue) response.getResponseValue();
               return cacheValue.toInternalCacheEntry(key);
            }
         }
      }

      // TODO If everyone returned null, and the read CH has changed, retry the remote get.
      // Otherwise our get command might be processed by the old owners after they have invalidated their data
      // and we'd return a null even though the key exists on
      return null;
   }

   protected final Object handleNonTxWriteCommand(InvocationContext ctx, DataWriteCommand command) throws Throwable {
      if (ctx.isInTxScope()) {
         throw new CacheException("Attempted execution of non-transactional write command in a transactional invocation context");
      }

      RecipientGenerator recipientGenerator = new SingleKeyRecipientGenerator(command.getKey());

      // see if we need to load values from remote sources first
      remoteGetBeforeWrite(ctx, command, recipientGenerator);

      // if this is local mode then skip distributing
      if (isLocalModeForced(command)) {
         return invokeNextInterceptor(ctx, command);
      }


      boolean isSync = isSynchronous(command);
      Address primaryOwner = cdl.getPrimaryOwner(command.getKey());
      int commandTopologyId = command.getTopologyId();
      int currentTopologyId = stateTransferManager.getCacheTopology().getTopologyId();
      // TotalOrderStateTransferInterceptor doesn't set the topology id for PFERs.
      // TODO Shouldn't PFERs be executed in a tx with total order?
      if (isSync && currentTopologyId != commandTopologyId && commandTopologyId != -1) {
         log.tracef("Cache topology changed while the command was executing: expected %d, got %d",
               commandTopologyId, currentTopologyId);
         throw new OutdatedTopologyException("Cache topology changed while the command was executing: expected " +
               commandTopologyId + ", got " + currentTopologyId);
      }

      if (!ctx.isOriginLocal()) {
         Object returnValue = invokeNextInterceptor(ctx, command);
         if (primaryOwner.equals(rpcManager.getAddress())) {
            if (command.isConditional() && !command.isSuccessful()) {
               log.tracef("Skipping the replication of the conditional command as it did not succeed on primary owner (%s).", command);
               return returnValue;
            }
            rpcManager.invokeRemotely(recipientGenerator.generateRecipients(), command, rpcManager.getDefaultRpcOptions(isSync));
         }
         return returnValue;
      } else {
         if (primaryOwner.equals(rpcManager.getAddress())) {
            Object result = invokeNextInterceptor(ctx, command);
            if (command.isConditional() && !command.isSuccessful()) {
               log.tracef("Skipping the replication of the conditional command as it did not succeed on primary owner (%s).", command);
               return result;
            }
            List<Address> recipients = recipientGenerator.generateRecipients();
            log.tracef("I'm the primary owner, sending the command to all (%s) the recipients in order to be applied.", recipients);
            // check if a single owner has been configured and the target for the key is the local address
            boolean isSingleOwnerAndLocal = cacheConfiguration.clustering().hash().numOwners() == 1;
            if (!isSingleOwnerAndLocal) {
               rpcManager.invokeRemotely(recipients, command, rpcManager.getDefaultRpcOptions(isSync));
            }
            return result;
         } else {
            log.tracef("I'm not the primary owner, so sending the command to the primary owner(%s) in order to be forwarded", primaryOwner);
            Object localResult = invokeNextInterceptor(ctx, command);
            boolean isSyncForwarding = isSync || isNeedReliableReturnValues(command);
            Map<Address, Response> addressResponseMap = rpcManager.invokeRemotely(Collections.singletonList(primaryOwner), command,
                  rpcManager.getDefaultRpcOptions(isSyncForwarding));
            if (!isSyncForwarding) return localResult;

            return getResponseFromPrimaryOwner(primaryOwner, addressResponseMap);
         }
      }
   }

   private Object getResponseFromPrimaryOwner(Address primaryOwner, Map<Address, Response> addressResponseMap) {
      Response fromPrimaryOwner = addressResponseMap.get(primaryOwner);
      if (fromPrimaryOwner == null) {
         log.tracef("Primary owner %s returned null", primaryOwner);
         return null;
      }
      if (fromPrimaryOwner.isSuccessful()) {
         return ((SuccessfulResponse) fromPrimaryOwner).getResponseValue();
      }

      if (addressResponseMap.get(primaryOwner) instanceof CacheNotFoundResponse) {
         // This means the cache wasn't running on the primary owner, so the command wasn't executed.
         // We throw an OutdatedTopologyException, StateTransferInterceptor will catch the exception and
         // it will then retry the command.
         throw new OutdatedTopologyException("Cache is no longer running on primary owner " + primaryOwner);
      }

      Throwable cause = fromPrimaryOwner instanceof ExceptionResponse ? ((ExceptionResponse)fromPrimaryOwner).getException() : null;
      throw new CacheException("Got unsuccessful response from primary owner: " + fromPrimaryOwner, cause);
   }

   protected abstract void remoteGetBeforeWrite(InvocationContext ctx, WriteCommand command, RecipientGenerator keygen) throws Throwable;

   interface RecipientGenerator {

      Collection<Object> getKeys();

      List<Address> generateRecipients();
   }

   class SingleKeyRecipientGenerator implements RecipientGenerator {
      private final Object key;
      private final Set<Object> keys;
      private List<Address> recipients = null;

      SingleKeyRecipientGenerator(Object key) {
         this.key = key;
         keys = Collections.singleton(key);
      }

      @Override
      public List<Address> generateRecipients() {
         if (recipients == null) {
            recipients = cdl.getOwners(key);
         }
         return recipients;
      }

      @Override
      public Collection<Object> getKeys() {
         return keys;
      }
   }

   class MultipleKeysRecipientGenerator implements RecipientGenerator {

      private final Collection<Object> keys;
      private List<Address> recipients = null;

      MultipleKeysRecipientGenerator(Collection<Object> keys) {
         this.keys = keys;
      }

      @Override
      public List<Address> generateRecipients() {
         if (recipients == null) {
            recipients = cdl.getOwners(keys);
         }
         return recipients;
      }

      @Override
      public Collection<Object> getKeys() {
         return keys;
      }
   }
}
