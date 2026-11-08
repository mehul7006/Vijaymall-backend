package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "purchase_item")
class PurchaseItemEntity extends BaseEntity {
  UUID purchaseId;
  UUID productId;
  int qty;
  long unitCostPaise;

  protected PurchaseItemEntity() {}
}
