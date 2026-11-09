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
  /** The most products one customer may save. */
  static final int MAX_ITEMS = 200;

  private final WishlistRepository repo;
  private final CatalogService catalog;
  private final Clock clock;

  /** Creates the service (wired by Spring). */
  public WishlistService(WishlistRepository repo, CatalogService catalog, Clock clock) {
    this.repo = repo;
    this.catalog = catalog;
    this.clock = clock;
  }

  /** Saved products, newest first. Products that were hidden since are left out. */
  public List<Product> list(UUID userId) { return catalog.visible(repo.productIds(userId)); }

  /**
   * Saves a product for the customer. Fails with 404 when the product is not visible to customers and with 422 when
   * the list already holds {@code MAX_ITEMS} products; saving one that is already there always succeeds.
   */
  public void add(UUID userId, UUID productId) {
    catalog.get(productId); // 404 for a product customers cannot see
    if (!repo.contains(userId, productId) && repo.count(userId) >= MAX_ITEMS) {
      throw ApiException.validation("productId", "wishlist is full (" + MAX_ITEMS + " items)");
    }
    repo.add(userId, productId, clock.instant());
  }

  /** Removes a product from the customer's list; removing one that is not there is harmless. */
  public void remove(UUID userId, UUID productId) { repo.remove(userId, productId); }
}
