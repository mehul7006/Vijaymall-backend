package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Row of {@code order_return_item}: how many units of one order line came back in a return and what was refunded for them.
 */
@Entity
@Table(name = "order_return_item")
class OrderReturnItemEntity extends BaseEntity {
  UUID returnId;
  UUID orderItemId;
  int qty;
  long refundPaise;
  boolean restocked;
  long couponSharePaise;

  /** For JPA. */
  protected OrderReturnItemEntity() {}
}
