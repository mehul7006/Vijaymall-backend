package com.vijay.mall.catalog.infra;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for the {@code product_review} table. */
interface ReviewJpa extends JpaRepository<ReviewEntity, UUID> {
  /** The review a given customer wrote for a product, if any. */
  Optional<ReviewEntity> findByProductIdAndUserId(UUID productId, UUID userId);

  /** One page of reviews for a single product. */
  Page<ReviewEntity> findByProductId(UUID productId, Pageable page);

  /** Number of reviews of a single product. */
  long countByProductId(UUID productId);

  /** One page of reviews across several products (the variants of one product). */
  Page<ReviewEntity> findByProductIdIn(Collection<UUID> productIds, Pageable page);

  /** Number of reviews across several products. */
  long countByProductIdIn(Collection<UUID> productIds);

  /** Average rating across several products; 0.0 when there are no reviews. */
  @Query("select coalesce(avg(r.rating), 0.0) from ReviewEntity r where r.productId in :pids")
  Double averageOf(@Param("pids") Collection<UUID> productIds);

  /** Average rating of one product; 0.0 when there are no reviews. */
  @Query("select coalesce(avg(r.rating), 0.0) from ReviewEntity r where r.productId = :pid")
  Double average(@Param("pid") UUID productId);
}
