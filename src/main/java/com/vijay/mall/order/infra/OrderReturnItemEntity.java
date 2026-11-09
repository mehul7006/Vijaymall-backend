package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "order_return_item")
class OrderReturnItemEntity extends BaseEntity {
  UUID returnId;
  UUID orderItemId;
  int qty;
  long refundPaise;
  boolean restocked;

  protected OrderReturnItemEntity() {}
}
