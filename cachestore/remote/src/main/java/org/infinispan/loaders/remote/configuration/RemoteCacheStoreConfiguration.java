package org.infinispan.loaders.remote.configuration;

import java.util.Collections;
import java.util.List;

import org.infinispan.commons.configuration.BuiltBy;
import org.infinispan.commons.configuration.ConfigurationFor;
import org.infinispan.commons.util.TypedProperties;
import org.infinispan.configuration.cache.AbstractStoreConfiguration;
import org.infinispan.configuration.cache.AsyncStoreConfiguration;
import org.infinispan.configuration.cache.SingletonStoreConfiguration;
import org.infinispan.loaders.remote.RemoteCacheStore;
import org.infinispan.loaders.remote.wrapper.EntryWrapper;

@BuiltBy(RemoteCacheStoreConfigurationBuilder.class)
@ConfigurationFor(RemoteCacheStore.class)
public class RemoteCacheStoreConfiguration extends AbstractStoreConfiguration {

   private final ExecutorFactoryConfiguration asyncExecutorFactory;
   private final String balancingStrategy;
   private final ConnectionPoolConfiguration connectionPool;
   private final long connectionTimeout;
   private final EntryWrapper<?, ?> entryWrapper;
   private final boolean forceReturnValues;
   private final boolean hotRodWrapping;
   private final int keySizeEstimate;
   private final String marshaller;
   private final boolean pingOnStartup;
   private final String protocolVersion;
   private final boolean rawValues;
   private final String remoteCacheName;
   private final List<RemoteServerConfiguration> servers;
   private final long socketTimeout;
   private final boolean tcpNoDelay;
   private final String transportFactory;
   private final int valueSizeEstimate;

   RemoteCacheStoreConfiguration(ExecutorFactoryConfiguration asyncExecutorFactory, String balancingStrategy,
         ConnectionPoolConfiguration connectionPool, long connectionTimeout, EntryWrapper<?, ?> entryWrapper, boolean forceReturnValues,
         boolean hotRodWrapping, int keySizeEstimate, String marshaller, boolean pingOnStartup, String protocolVersion, boolean rawValues,
         String remoteCacheName, List<RemoteServerConfiguration> servers, long socketTimeout, boolean tcpNoDelay, String transportFactory,
         int valueSizeEstimate, boolean purgeOnStartup, boolean purgeSynchronously, int purgerThreads,
         boolean fetchPersistentState, boolean ignoreModifications, TypedProperties properties,
         AsyncStoreConfiguration asyncStoreConfiguration, SingletonStoreConfiguration singletonStoreConfiguration) {
      super(purgeOnStartup, purgeSynchronously, purgerThreads, fetchPersistentState, ignoreModifications, properties,
            asyncStoreConfiguration, singletonStoreConfiguration);
      this.asyncExecutorFactory = asyncExecutorFactory;
      this.balancingStrategy = balancingStrategy;
      this.connectionPool = connectionPool;
      this.connectionTimeout = connectionTimeout;
      this.entryWrapper = entryWrapper;
      this.forceReturnValues = forceReturnValues;
      this.hotRodWrapping = hotRodWrapping;
      this.keySizeEstimate = keySizeEstimate;
      this.marshaller = marshaller;
      this.pingOnStartup = pingOnStartup;
      this.protocolVersion = protocolVersion;
      this.rawValues = rawValues;
      this.remoteCacheName = remoteCacheName;
      this.servers = Collections.unmodifiableList(servers);
      this.socketTimeout = socketTimeout;
      this.tcpNoDelay = tcpNoDelay;
      this.transportFactory = transportFactory;
      this.valueSizeEstimate = valueSizeEstimate;
   }

   public ExecutorFactoryConfiguration asyncExecutorFactory() {
      return asyncExecutorFactory;
   }

   public String balancingStrategy() {
      return balancingStrategy;
   }

   public ConnectionPoolConfiguration connectionPool() {
      return connectionPool;
   }

   public long connectionTimeout() {
      return connectionTimeout;
   }

   public EntryWrapper<?, ?> entryWrapper() {
      return entryWrapper;
   }

   public boolean forceReturnValues() {
      return forceReturnValues;
   }

   public boolean hotRodWrapping() {
      return hotRodWrapping;
   }

   public int keySizeEstimate() {
      return keySizeEstimate;
   }

   public String marshaller() {
      return marshaller;
   }

   public boolean pingOnStartup() {
      return pingOnStartup;
   }

   public String protocolVersion() {
      return protocolVersion;
   }

   public boolean rawValues() {
      return rawValues;
   }

   public String remoteCacheName() {
      return remoteCacheName;
   }

   public List<RemoteServerConfiguration> servers() {
      return servers;
   }

   public long socketTimeout() {
      return socketTimeout;
   }

   public boolean tcpNoDelay() {
      return tcpNoDelay;
   }

   public String transportFactory() {
      return transportFactory;
   }

   public int valueSizeEstimate() {
      return valueSizeEstimate;
   }
}

