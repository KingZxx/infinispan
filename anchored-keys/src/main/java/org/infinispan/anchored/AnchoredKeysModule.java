package org.infinispan.anchored;

import static org.infinispan.commons.logging.Log.CONFIG;

import org.infinispan.anchored.configuration.AnchoredKeysConfiguration;
import org.infinispan.anchored.impl.AnchorManager;
import org.infinispan.anchored.impl.AnchoredDistributionInterceptor;
import org.infinispan.anchored.impl.AnchoredEntryFactory;
import org.infinispan.anchored.impl.AnchoredFetchInterceptor;
import org.infinispan.anchored.impl.AnchoredStateProvider;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.container.impl.EntryFactory;
import org.infinispan.conflict.MergePolicy;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.factories.annotations.InfinispanModule;
import org.infinispan.factories.impl.BasicComponentRegistry;
import org.infinispan.factories.impl.DynamicModuleMetadataProvider;
import org.infinispan.factories.impl.ModuleMetadataBuilder;
import org.infinispan.interceptors.AsyncInterceptorChain;
import org.infinispan.interceptors.impl.ClusteringInterceptor;
import org.infinispan.lifecycle.ModuleLifecycle;
import org.infinispan.partitionhandling.PartitionHandling;
import org.infinispan.statetransfer.StateProvider;

/**
 * Install the required components for stable distribution caches.
 *
 * @author Dan Berindei
 * @since 11
 */
@InfinispanModule(name = "anchored-keys", requiredModules = "core")
public final class AnchoredKeysModule implements ModuleLifecycle, DynamicModuleMetadataProvider {

   public static final String ANCHORED_KEYS_FEATURE = "anchored-keys";

   private GlobalConfiguration globalConfiguration;

   @Override
   public void registerDynamicMetadata(ModuleMetadataBuilder.ModuleBuilder moduleBuilder, GlobalConfiguration globalConfiguration) {
   }

   @Override
   public void cacheManagerStarting(GlobalComponentRegistry gcr, GlobalConfiguration globalConfiguration) {
      this.globalConfiguration = globalConfiguration;
   }

   @Override
   public void cacheStarting(ComponentRegistry cr, Configuration configuration, String cacheName) {
      AnchoredKeysConfiguration anchoredKeysConfiguration = configuration.module(AnchoredKeysConfiguration.class);
      if (anchoredKeysConfiguration == null || !anchoredKeysConfiguration.enabled())
         return;

      assert configuration.clustering().cacheMode().isReplicated();
      assert !configuration.clustering().stateTransfer().awaitInitialTransfer();
      assert configuration.clustering().partitionHandling().whenSplit() == PartitionHandling.ALLOW_READ_WRITES;
      assert configuration.clustering().partitionHandling().mergePolicy() == MergePolicy.PREFERRED_NON_NULL;

      if (!globalConfiguration.features().isAvailable(ANCHORED_KEYS_FEATURE))
         throw CONFIG.featureDisabled(ANCHORED_KEYS_FEATURE);

      cr.registerComponent(new AnchorManager(), AnchorManager.class);

      AsyncInterceptorChain interceptorChain = cr.getComponent(AsyncInterceptorChain.class);

      // Replace the clustering interceptor with our custom interceptor
      ClusteringInterceptor oldDistInterceptor = interceptorChain.findInterceptorExtending(ClusteringInterceptor.class);
      AnchoredDistributionInterceptor distInterceptor = new AnchoredDistributionInterceptor();
      cr.registerComponent(distInterceptor, AnchoredDistributionInterceptor.class);
      boolean interceptorAdded = interceptorChain.addInterceptorBefore(distInterceptor, oldDistInterceptor.getClass());
      assert interceptorAdded;
      interceptorChain.removeInterceptor(oldDistInterceptor.getClass());

      // Add a separate interceptor to fetch the actual values
      // AnchoredDistributionInterceptor cannot do it because it extends NonTxDistributionInterceptor
      AnchoredFetchInterceptor fetchInterceptor = new AnchoredFetchInterceptor();
      cr.registerComponent(fetchInterceptor, AnchoredFetchInterceptor.class);
      interceptorAdded = interceptorChain.addInterceptorAfter(fetchInterceptor, AnchoredDistributionInterceptor.class);
      assert interceptorAdded;

      BasicComponentRegistry bcr = cr.getComponent(BasicComponentRegistry.class);
      bcr.replaceComponent(StateProvider.class.getName(), new AnchoredStateProvider(), true);
      bcr.replaceComponent(EntryFactory.class.getName(), new AnchoredEntryFactory(), true);
      cr.rewire();
   }
}
