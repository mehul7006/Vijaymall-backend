package com.vijay.mall.wishlist.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.wishlist.infra.WishlistRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Products a customer saved for later. Adding and removing are repeatable without error. */
@Service
public class WishlistService {
  static final int MAX_ITEMS = 200;

  private final WishlistRepository repo;
  private final CatalogService catalog;
  private final Clock clock;

  public WishlistService(WishlistRepository repo, CatalogService catalog, Clock clock) {
    this.repo = repo;
    this.catalog = catalog;
    this.clock = clock;
  }

  /** Saved products, newest first. Products that were hidden since are left out. */
  public List<Product> list(UUID userId) { return catalog.visible(repo.productIds(userId)); }

  public void add(UUID userId, UUID productId) {
    catalog.get(productId); // 404 for a product customers cannot see
    if (!repo.contains(userId, productId) && repo.count(userId) >= MAX_ITEMS) {
      throw ApiException.validation("productId", "wishlist is full (" + MAX_ITEMS + " items)");
    }
    repo.add(userId, productId, clock.instant());
  }

  public void remove(UUID userId, UUID productId) { repo.remove(userId, productId); }
}
