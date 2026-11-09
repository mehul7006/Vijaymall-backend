package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * JPA entity for the {@code purchase_item} table: one product line of a delivery with the quantity received and the cost per unit in paise.
 */
@Entity
@Table(name = "purchase_item")
class PurchaseItemEntity extends BaseEntity {
  UUID purchaseId;
  UUID productId;
  int qty;
  long unitCostPaise;

  /** No-argument constructor required by JPA; not for application code. */
  protected PurchaseItemEntity() {}
}
