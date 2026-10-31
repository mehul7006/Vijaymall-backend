package com.vijay.mall.inventory.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory")
class InventoryEntity {
  @Id UUID productId;
  int qtyOnHand;
  int lowThreshold;
  Instant updatedAt;

  protected InventoryEntity() {}
}
