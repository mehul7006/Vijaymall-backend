package com.vijay.mall.catalog.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProductJpa extends JpaRepository<ProductEntity, UUID> {
  @org.springframework.data.jpa.repository.Query("select max(p.updatedAt) from ProductEntity p")
  java.time.Instant lastChange();
}
