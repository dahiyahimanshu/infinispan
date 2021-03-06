package org.infinispan.interceptors;

import org.infinispan.atomic.AtomicHashMap;
import org.infinispan.commands.AbstractVisitor;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.tx.CommitCommand;
import org.infinispan.commands.tx.PrepareCommand;
import org.infinispan.commands.tx.RollbackCommand;
import org.infinispan.commands.write.*;
import org.infinispan.commons.util.CollectionFactory;
import org.infinispan.configuration.cache.LoadersConfiguration;
import org.infinispan.container.InternalEntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.DeltaAwareCacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.versioning.EntryVersion;
import org.infinispan.container.versioning.EntryVersionsMap;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.Start;
import org.infinispan.interceptors.base.JmxStatsCommandInterceptor;
import org.infinispan.jmx.annotations.MBean;
import org.infinispan.jmx.annotations.ManagedAttribute;
import org.infinispan.jmx.annotations.ManagedOperation;
import org.infinispan.jmx.annotations.MeasurementType;
import org.infinispan.loaders.CacheLoaderException;
import org.infinispan.loaders.manager.CacheLoaderManager;
import org.infinispan.loaders.modifications.Clear;
import org.infinispan.loaders.modifications.Modification;
import org.infinispan.loaders.modifications.Remove;
import org.infinispan.loaders.modifications.Store;
import org.infinispan.loaders.spi.CacheStore;
import org.infinispan.metadata.EmbeddedMetadata;
import org.infinispan.metadata.Metadata;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import javax.transaction.Transaction;
import javax.transaction.TransactionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes modifications back to the store on the way out: stores modifications back through the CacheLoader, either
 * after each method call (no TXs), or at TX commit.
 *
 * Only used for LOCAL and INVALIDATION caches.
 *
 * @author Bela Ban
 * @author Dan Berindei
 * @since 4.0
 */
@MBean(objectName = "CacheStore", description = "Component that handles storing of entries to a CacheStore from memory.")
public class CacheStoreInterceptor extends JmxStatsCommandInterceptor {
   LoadersConfiguration loaderConfig = null;
   private Map<GlobalTransaction, Integer> txStores;
   private Map<GlobalTransaction, Set<Object>> preparingTxs;
   final AtomicLong cacheStores = new AtomicLong(0);
   CacheStore store;
   private CacheLoaderManager loaderManager;
   private InternalEntryFactory entryFactory;
   private TransactionManager transactionManager;
   protected volatile boolean enabled = true;

   private static final Log log = LogFactory.getLog(CacheStoreInterceptor.class);

   @Override
   protected Log getLog() {
      return log;
   }

   @Inject
   protected void init(CacheLoaderManager loaderManager, InternalEntryFactory entryFactory, TransactionManager transactionManager) {
      this.loaderManager = loaderManager;
      this.entryFactory = entryFactory;
      this.transactionManager = transactionManager;
   }

   @Start(priority = 15)
   protected void start() {
      store = loaderManager.getCacheStore();
      this.setStatisticsEnabled(cacheConfiguration.jmxStatistics().enabled());
      loaderConfig = cacheConfiguration.loaders();
      int concurrencyLevel = cacheConfiguration.locking().concurrencyLevel();
      txStores = CollectionFactory.makeConcurrentMap(64, concurrencyLevel);
      preparingTxs = CollectionFactory.makeConcurrentMap(64, concurrencyLevel);
   }
   @Override
   public Object visitCommitCommand(TxInvocationContext ctx, CommitCommand command) throws Throwable {
      if (isStoreEnabled())
         commitCommand(ctx);

      return invokeNextInterceptor(ctx, command);
   }

   protected void commitCommand(TxInvocationContext ctx) throws Throwable {
      if (!ctx.getCacheTransaction().getAllModifications().isEmpty()) {
         // this is a commit call.
         GlobalTransaction tx = ctx.getGlobalTransaction();
         if (getLog().isTraceEnabled()) getLog().tracef("Calling loader.commit() for transaction %s", tx);

         //hack for ISPN-586. This should be dropped once a proper fix for ISPN-604 is in place
         Transaction xaTx = null;
         if (transactionManager != null) {
            xaTx = transactionManager.suspend();
         }

         try {
            store.commit(tx);
         } finally {
            // Regardless of outcome, remove from preparing txs
            preparingTxs.remove(tx);

            //part of the hack for ISPN-586
            if (transactionManager != null && xaTx != null) {
               transactionManager.resume(xaTx);
            }
         }
         if (getStatisticsEnabled()) {
            Integer puts = txStores.get(tx);
            if (puts != null) {
               cacheStores.getAndAdd(puts);
            }
            txStores.remove(tx);
         }
      } else {
         if (getLog().isTraceEnabled()) getLog().trace("Commit called with no modifications; ignoring.");
      }
   }

   @Override
   public Object visitRollbackCommand(TxInvocationContext ctx, RollbackCommand command) throws Throwable {
      if (isStoreEnabled()) {
         if (getLog().isTraceEnabled()) getLog().trace("Transactional so don't put stuff in the cache store yet.");
         if (!ctx.getCacheTransaction().getAllModifications().isEmpty()) {
            GlobalTransaction tx = ctx.getGlobalTransaction();
            // this is a rollback method
            if (preparingTxs.containsKey(tx)) {
               preparingTxs.remove(tx);
               store.rollback(tx);
            }
            if (getStatisticsEnabled()) txStores.remove(tx);
         } else {
            if (getLog().isTraceEnabled()) getLog().trace("Rollback called with no modifications; ignoring.");
         }
      }
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public Object visitPrepareCommand(TxInvocationContext ctx, PrepareCommand command) throws Throwable {
      if (isStoreEnabled()) {
         if (getLog().isTraceEnabled()) getLog().trace("Transactional so don't put stuff in the cache store yet.");
         prepareCacheLoader(ctx, command.getGlobalTransaction(), ctx, command.isOnePhaseCommit());
      }
      return invokeNextInterceptor(ctx, command);
   }

   @Override
   public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
      Object retval = invokeNextInterceptor(ctx, command);
      if (!isStoreEnabled(command) || ctx.isInTxScope() || !command.isSuccessful()) return retval;
      if (!isProperWriter(ctx, command, command.getKey())) return retval;

      Object key = command.getKey();
      boolean resp = store.remove(key);
      if (getLog().isTraceEnabled()) getLog().tracef("Removed entry under key %s and got response %s from CacheStore", key, resp);
      return retval;
   }

   @Override
   public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
      if (isStoreEnabled(command) && !ctx.isInTxScope() && isProperWriterForClear(ctx))
         clearCacheStore();

      return invokeNextInterceptor(ctx, command);
   }

   protected void clearCacheStore() throws CacheLoaderException {
      store.clear();
      if (getLog().isTraceEnabled()) getLog().trace("Cleared cache store");
   }

   @Override
   public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
      Object returnValue = invokeNextInterceptor(ctx, command);
      if (!isStoreEnabled(command) || ctx.isInTxScope() || !command.isSuccessful()) return returnValue;
      if (!isProperWriter(ctx, command, command.getKey())) return returnValue;

      Object key = command.getKey();
      InternalCacheEntry se = getStoredEntry(key, ctx);
      store.store(se);
      if (getLog().isTraceEnabled()) getLog().tracef("Stored entry %s under key %s", se, key);
      if (getStatisticsEnabled()) cacheStores.incrementAndGet();

      return returnValue;
   }

   @Override
   public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
      Object returnValue = invokeNextInterceptor(ctx, command);
      if (!isStoreEnabled(command) || ctx.isInTxScope() || !command.isSuccessful()) return returnValue;
      if (!isProperWriter(ctx, command, command.getKey())) return returnValue;

      Object key = command.getKey();
      InternalCacheEntry se = getStoredEntry(key, ctx);
      store.store(se);
      if (getLog().isTraceEnabled()) getLog().tracef("Stored entry %s under key %s", se, key);
      if (getStatisticsEnabled()) cacheStores.incrementAndGet();

      return returnValue;
   }

   @Override
   public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
      Object returnValue = invokeNextInterceptor(ctx, command);
      if (!isStoreEnabled(command) || ctx.isInTxScope()) return returnValue;

      Map<Object, Object> map = command.getMap();
      for (Object key : map.keySet()) {
         if (isProperWriter(ctx, command, key)) {
            InternalCacheEntry se = getStoredEntry(key, ctx);
            store.store(se);
            if (getLog().isTraceEnabled()) getLog().tracef("Stored entry %s under key %s", se, key);
         }
      }
      if (getStatisticsEnabled()) cacheStores.getAndAdd(map.size());
      return returnValue;
   }

   protected final void prepareCacheLoader(TxInvocationContext ctx, GlobalTransaction gtx, TxInvocationContext transactionContext, boolean onePhase) throws Throwable {
      if (transactionContext == null) {
         throw new Exception("transactionContext for transaction " + gtx + " not found in transaction table");
      }

      List<WriteCommand> modifications = transactionContext.getCacheTransaction().getAllModifications();
      if (modifications.isEmpty()) {
         if (getLog().isTraceEnabled()) getLog().trace("Transaction has not logged any modifications!");
         return;
      }
      if (getLog().isTraceEnabled()) getLog().tracef("Cache loader modification list: %s", modifications);
      StoreModificationsBuilder modsBuilder = new StoreModificationsBuilder(getStatisticsEnabled(), modifications.size());
      for (WriteCommand cacheCommand : modifications) {
         if (isStoreEnabled(cacheCommand)) {
            cacheCommand.acceptVisitor(ctx, modsBuilder);
         }
      }
      int numMods = modsBuilder.modifications.size();
      if (getLog().isTraceEnabled()) getLog().tracef("Converted method calls to cache loader modifications.  List size: %s", numMods);

      if (numMods > 0) {
         GlobalTransaction tx = transactionContext.getGlobalTransaction();
         store.prepare(modsBuilder.modifications, tx, onePhase);


         boolean shouldCountStores = getStatisticsEnabled() && modsBuilder.putCount > 0;
         if (!onePhase) {
            preparingTxs.put(tx, modsBuilder.affectedKeys);
            if (shouldCountStores) {
               txStores.put(tx, modsBuilder.putCount);
            }
         } else if (shouldCountStores) {
            cacheStores.getAndAdd(modsBuilder.putCount);
         }
      }
   }

   protected boolean isStoreEnabled() {
      if (!enabled)
         return false;

      if (store == null) {
         log.trace("Skipping cache store because the cache loader does not implement CacheStore");
         return false;
      }
      return true;
   }

   protected boolean isStoreEnabled(FlagAffectedCommand command) {
      if (!isStoreEnabled())
         return false;

      if (command.hasFlag(Flag.SKIP_CACHE_STORE)) {
         log.trace("Skipping cache store since the call contain a skip cache store flag");
         return false;
      }
      if (loaderConfig.shared() && command.hasFlag(Flag.SKIP_SHARED_CACHE_STORE)) {
         log.trace("Skipping cache store since it is shared and the call contain a skip shared cache store flag");
         return false;
      }
      return true;
   }

   protected boolean isProperWriter(InvocationContext ctx, FlagAffectedCommand command, Object key) {
      // In invalidation mode we can have remote invalidation commands, and we don't want them to remove
      // entries from a shared cache store.
      return !loaderConfig.shared() || ctx.isOriginLocal();
   }

   protected boolean isProperWriterForClear(InvocationContext ctx) {
      return true;
   }

   public class StoreModificationsBuilder extends AbstractVisitor {

      private final boolean generateStatistics;
      int putCount;
      private final Set<Object> affectedKeys;
      private final List<Modification> modifications;

      public StoreModificationsBuilder(boolean generateStatistics, int numMods) {
         this.generateStatistics = generateStatistics;
         affectedKeys = new HashSet<Object>(numMods);
         modifications = new ArrayList<Modification>(numMods);
      }

      @Override
      public Object visitPutKeyValueCommand(InvocationContext ctx, PutKeyValueCommand command) throws Throwable {
         return visitSingleStore(ctx, command, command.getKey());
      }

      @Override
      public Object visitApplyDeltaCommand(InvocationContext ctx, ApplyDeltaCommand command) throws Throwable {
         if (isProperWriter(ctx, command, command.getKey())) {
            if (generateStatistics) putCount++;
            CacheEntry entry = ctx.lookupEntry(command.getKey());
            InternalCacheEntry ice;
            if (entry instanceof InternalCacheEntry) {
               ice = (InternalCacheEntry) entry;
            } else if (entry instanceof DeltaAwareCacheEntry) {
               AtomicHashMap<?,?> uncommittedChanges = ((DeltaAwareCacheEntry) entry).getUncommittedChages();
               ice = entryFactory.create(entry.getKey(), uncommittedChanges, entry.getMetadata(), entry.getLifespan(), entry.getMaxIdle());
            } else {
               ice = entryFactory.create(entry);
            }

            modifications.add(new Store(ice));
            affectedKeys.add(command.getKey());
         }
         return null;
      }

      @Override
      public Object visitReplaceCommand(InvocationContext ctx, ReplaceCommand command) throws Throwable {
         return visitSingleStore(ctx, command, command.getKey());
      }

      @Override
      public Object visitPutMapCommand(InvocationContext ctx, PutMapCommand command) throws Throwable {
         Map<Object, Object> map = command.getMap();
         for (Object key : map.keySet())
            visitSingleStore(ctx, command, key);
         return null;
      }

      @Override
      public Object visitRemoveCommand(InvocationContext ctx, RemoveCommand command) throws Throwable {
         Object key = command.getKey();
         if (isProperWriter(ctx, command, key)) {
            modifications.add(new Remove(key));
            affectedKeys.add(command.getKey());
         }
         return null;
      }

      @Override
      public Object visitClearCommand(InvocationContext ctx, ClearCommand command) throws Throwable {
         if (isProperWriterForClear(ctx)) {
            modifications.add(new Clear());
         }
         return null;
      }

      private Object visitSingleStore(InvocationContext ctx, FlagAffectedCommand command, Object key) throws Throwable {
         if (isProperWriter(ctx, command, key)) {
            if (generateStatistics) putCount++;
            modifications.add(new Store(getStoredEntry(key, ctx)));
            affectedKeys.add(key);
         }
         return null;
      }
   }

   @Override
   @ManagedOperation(
         description = "Resets statistics gathered by this component",
         displayName = "Reset statistics"
   )
   public void resetStatistics() {
      cacheStores.set(0);
   }

   @ManagedAttribute(
         description = "number of cache loader stores",
         displayName = "Number of cache stores",
         measurementType = MeasurementType.TRENDSUP
   )
   public long getCacheLoaderStores() {
      return cacheStores.get();
   }

   InternalCacheEntry getStoredEntry(Object key, InvocationContext ctx) {
      CacheEntry entry = ctx.lookupEntry(key);
      if (entry instanceof InternalCacheEntry) {
         return (InternalCacheEntry) entry;
      } else {
         if (ctx.isInTxScope()) {
            EntryVersionsMap updatedVersions =
                  ((TxInvocationContext) ctx).getCacheTransaction().getUpdatedEntryVersions();
            if (updatedVersions != null) {
               EntryVersion version = updatedVersions.get(entry.getKey());
               if (version != null) {
                  Metadata metadata = entry.getMetadata();
                  if (metadata == null) {
                     // If no metadata passed, assumed embedded metadata
                     metadata = new EmbeddedMetadata.Builder()
                           .lifespan(entry.getLifespan()).maxIdle(entry.getMaxIdle())
                           .version(version).build();
                     return entryFactory.create(entry.getKey(), entry.getValue(), metadata);
                  } else {
                     metadata = metadata.builder().version(version).build();
                     return entryFactory.create(entry.getKey(), entry.getValue(), metadata);
                  }
               }
            }
         }

         return entryFactory.create(entry);
      }
   }

   public void disableInterceptor() {
      enabled = false;
   }

   public Map<GlobalTransaction, Set<Object>> getPreparingTxs() {
      return Collections.unmodifiableMap(preparingTxs);
   }

   public Map<GlobalTransaction, Integer> getTxStores() {
      return Collections.unmodifiableMap(txStores);
   }
}
