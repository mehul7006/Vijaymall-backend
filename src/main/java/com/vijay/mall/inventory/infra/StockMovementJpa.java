package com.vijay.mall.inventory.infra;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface StockMovementJpa extends JpaRepository<StockMovementEntity, UUID> {
  Page<StockMovementEntity> findByProductId(UUID productId, Pageable page);
}
