package com.vijay.mall.catalog.infra;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Spring Data repository for the {@code product} table. */
interface ProductJpa extends JpaRepository<ProductEntity, UUID> {
  /** The latest {@code updatedAt} of any product, or null when there are none. */
  @Query("select max(p.updatedAt) from ProductEntity p")
  Instant lastChange();
}
