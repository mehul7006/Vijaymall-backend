package com.vijay.mall.catalog.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReviewJpa extends JpaRepository<ReviewEntity, UUID> {
  Optional<ReviewEntity> findByProductIdAndUserId(UUID productId, UUID userId);

  Page<ReviewEntity> findByProductId(UUID productId, Pageable page);

  long countByProductId(UUID productId);

  @Query("select coalesce(avg(r.rating), 0.0) from ReviewEntity r where r.productId = :pid")
  Double average(@Param("pid") UUID productId);
}
