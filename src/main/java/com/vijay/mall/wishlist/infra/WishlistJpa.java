package com.vijay.mall.wishlist.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface WishlistJpa extends JpaRepository<WishlistItemEntity, UUID> {
  List<WishlistItemEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

  long countByUserId(UUID userId);

  boolean existsByUserIdAndProductId(UUID userId, UUID productId);

  /** Idempotent: saving the same product twice, even at the same moment, leaves one row. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "insert into wishlist_item (id, user_id, product_id, created_at) values (:id, :u, :p, :now) "
      + "on conflict (user_id, product_id) do nothing", nativeQuery = true)
  int addIfAbsent(@Param("id") UUID id, @Param("u") UUID userId, @Param("p") UUID productId, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from WishlistItemEntity w where w.userId = :u and w.productId = :p")
  int remove(@Param("u") UUID userId, @Param("p") UUID productId);
}
