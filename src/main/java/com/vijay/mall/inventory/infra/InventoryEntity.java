package com.vijay.mall.inventory.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code inventory} table: the units on hand and the low-stock alert level of one product (the product id is the key).
 */
@Entity
@Table(name = "inventory")
class InventoryEntity {
  @Id UUID productId;
  int qtyOnHand;
  int lowThreshold;
  Instant updatedAt;

  /** No-argument constructor required by JPA; not for application code. */
  protected InventoryEntity() {}
}
