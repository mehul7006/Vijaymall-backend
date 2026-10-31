package com.vijay.mall.inventory.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stock_movement")
class StockMovementEntity extends BaseEntity {
  UUID productId;
  int delta;
  String reason;
  UUID refId;
  UUID actorId;
  Instant createdAt;

  protected StockMovementEntity() {}
}
