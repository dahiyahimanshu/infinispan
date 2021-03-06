package org.infinispan.configuration.cache;

import java.util.Properties;

import org.infinispan.commons.CacheException;
import org.infinispan.commons.util.TypedProperties;
import org.infinispan.loaders.spi.CacheLoader;
import org.infinispan.loaders.spi.CacheStore;

/**
 * Configuration a legacy cache loader, i.e. a loader which is still configured via properties and
 * does not yet provide a builder interface
 *
 * @author Pete Muir
 * @author Tristan Tarrant
 * @since 5.1
 *
 */
public class LegacyLoaderConfigurationBuilder extends AbstractLoaderConfigurationBuilder<LegacyLoaderConfiguration, LegacyLoaderConfigurationBuilder> {

   private CacheLoader cacheLoader;
   private Properties properties = new Properties();

   public LegacyLoaderConfigurationBuilder(LoadersConfigurationBuilder builder) {
      super(builder);
   }

   @Override
   public LegacyLoaderConfigurationBuilder self() {
      return this;
   }

   /**
    * NOTE: Currently Infinispan will not use the object instance, but instead instantiate a new
    * instance of the class. Therefore, do not expect any state to survive, and provide a no-args
    * constructor to any instance. This will be resolved in Infinispan 5.2.0
    *
    * @param cacheLoader
    * @return
    */
   public LegacyLoaderConfigurationBuilder cacheLoader(CacheLoader cacheLoader) {
      this.cacheLoader = cacheLoader;
      return this;
   }

   /**
    * <p>
    * Defines a single property. Can be used multiple times to define all needed properties, but the
    * full set is overridden by {@link #withProperties(Properties)}.
    * </p>
    * <p>
    * These properties are passed directly to the cache loader.
    * </p>
    */
   @Override
   public LegacyLoaderConfigurationBuilder addProperty(String key, String value) {
      this.properties.put(key, value);
      return this;
   }

   /**
    * <p>
    * These properties are passed directly to the cache loader.
    * </p>
    */
   @Override
   public LegacyLoaderConfigurationBuilder withProperties(Properties props) {
      this.properties = props;
      return this;
   }

   @Override
   public void validate() {
      if(cacheLoader instanceof CacheStore)
         throw new CacheException("Attempt to use a CacheStore as a loader");
   }

   @Override
   public LegacyLoaderConfiguration create() {
      return new LegacyLoaderConfiguration(TypedProperties.toTypedProperties(properties), cacheLoader);
   }

   @Override
   public LegacyLoaderConfigurationBuilder read(LegacyLoaderConfiguration template) {
      this.cacheLoader = template.cacheLoader();
      this.properties = template.properties();

      return this;
   }

   @Override
   public String toString() {
      return "LoaderConfigurationBuilder{" +
            "cacheLoader=" + cacheLoader +
            '}';
   }

}
