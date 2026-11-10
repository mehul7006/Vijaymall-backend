package com.vijay.mall.pos.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code product_pos}, the counter attributes of products; also tells when any of them last
 * changed.
 */
interface PosProductJpa extends JpaRepository<ProductPosEntity, UUID> {
  Optional<ProductPosEntity> findByPlu(String plu);

  @Query("select max(p.updatedAt) from ProductPosEntity p")
  Instant lastChange();

  @Query("select p.productId from ProductPosEntity p where p.productId in :ids")
  List<UUID> configuredIds(@Param("ids") Collection<UUID> ids);

  @Query("select p from ProductPosEntity p where p.productId in :ids")
  List<ProductPosEntity> byIds(@Param("ids") Collection<UUID> ids);
}
