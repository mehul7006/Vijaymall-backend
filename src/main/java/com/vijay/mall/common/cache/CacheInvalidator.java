package com.vijay.mall.common.cache;

import com.vijay.mall.common.cache.AppCaches.Name;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.config.SettingsChanged;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Empties the right caches the moment the data behind them changes. The services already announce changes (CatalogChanged with an
 * area) for the live screens; the same announcement is used here, and only after the change has committed. A request that
 * reads the old data after the commit but before this runs can still fill the cache once, so the cache also refuses to store any
 * result whose load began before the cache was emptied (see {@link AppCaches#get}); this listener then removes whatever was stored earlier.
 */
@Component
public class CacheInvalidator {
  private final AppCaches caches;

  /** Creates the invalidator. */
  public CacheInvalidator(AppCaches caches) { this.caches = caches; }

  /** A new expiry time or switching caching on/off applies to everything at once, so start from empty. */
  @EventListener
  public void on(SettingsChanged e) {
    if (e.keys().stream().anyMatch(k -> k.startsWith("cache."))) caches.invalidateAll();
  }

  /** Empties the caches that hold the changed area, after the change has committed. */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void on(CatalogChanged e) {
    switch (e.area()) {
      case CatalogChanged.CATALOG -> {
        caches.invalidate(Name.CATEGORIES);
        caches.invalidate(Name.BANNERS);
        caches.invalidate(Name.PRODUCT_LIST);
      }
      case CatalogChanged.COUPONS -> caches.invalidate(Name.COUPONS);
      case CatalogChanged.UNITS -> caches.invalidate(Name.UNITS);
      case CatalogChanged.SHOP -> {
        caches.invalidate(Name.SHOP);
        caches.invalidate(Name.SHOP_PROFILE);
      }
      case CatalogChanged.PAYMENT_METHODS -> caches.invalidate(Name.PAYMENT_METHODS);
      case CatalogChanged.FLASH -> caches.invalidate(Name.FLASH);
      default -> { /* an area nothing is cached for */ }
    }
  }
}
