package com.vijay.mall.wishlist.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores the products customers saved for later. */
@Repository
public class WishlistRepository {
  private final WishlistJpa jpa;

  WishlistRepository(WishlistJpa jpa) { this.jpa = jpa; }

  /** Product ids, most recently saved first. */
  public List<UUID> productIds(UUID userId) {
    return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream().map(e -> e.productId).toList();
  }

  /** How many products the customer has saved. */
  public long count(UUID userId) { return jpa.countByUserId(userId); }

  /** True when the customer already saved the product. */
  public boolean contains(UUID userId, UUID productId) { return jpa.existsByUserIdAndProductId(userId, productId); }

  /** Saves the product; saving it again is harmless and keeps one row. */
  @Transactional
  public void add(UUID userId, UUID productId, Instant now) { jpa.addIfAbsent(UUID.randomUUID(), userId, productId, now); }

  /** Removes the product from the customer's list; removing one that is not there is harmless. */
  @Transactional
  public void remove(UUID userId, UUID productId) { jpa.remove(userId, productId); }
}
