package com.vijay.mall.catalog.infra;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProductJpa extends JpaRepository<ProductEntity, UUID> {
  @Query("select max(p.updatedAt) from ProductEntity p")
  Instant lastChange();
}
