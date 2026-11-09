package com.vijay.mall.inventory.infra;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the {@code stock_movement} table. */
interface StockMovementJpa extends JpaRepository<StockMovementEntity, UUID> {
  /** One page of the stock history of a single product. */
  Page<StockMovementEntity> findByProductId(UUID productId, Pageable page);
}
