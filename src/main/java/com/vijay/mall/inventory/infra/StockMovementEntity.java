package com.vijay.mall.inventory.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code stock_movement} table: one line of stock history with the signed change, the reason, the order or document behind it and who did it.
 */
@Entity
@Table(name = "stock_movement")
class StockMovementEntity extends BaseEntity {
  UUID productId;
  int delta;
  String reason;
  UUID refId;
  UUID actorId;
  Instant createdAt;

  /** No-argument constructor required by JPA; not for application code. */
  protected StockMovementEntity() {}
}
