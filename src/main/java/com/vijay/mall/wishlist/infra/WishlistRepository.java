package com.vijay.mall.wishlist.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class WishlistRepository {
  private final WishlistJpa jpa;

  WishlistRepository(WishlistJpa jpa) { this.jpa = jpa; }

  /** Product ids, most recently saved first. */
  public List<UUID> productIds(UUID userId) {
    return jpa.findByUserIdOrderByCreatedAtDesc(userId).stream().map(e -> e.productId).toList();
  }

  public long count(UUID userId) { return jpa.countByUserId(userId); }

  public boolean contains(UUID userId, UUID productId) { return jpa.existsByUserIdAndProductId(userId, productId); }

  @Transactional
  public void add(UUID userId, UUID productId, Instant now) { jpa.addIfAbsent(UUID.randomUUID(), userId, productId, now); }

  @Transactional
  public void remove(UUID userId, UUID productId) { jpa.remove(userId, productId); }
}
