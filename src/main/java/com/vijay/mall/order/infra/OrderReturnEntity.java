package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Row of {@code order_return}: one return recorded on an order, with the refund and the coin movements. */
@Entity
@Table(name = "order_return")
class OrderReturnEntity extends BaseEntity {
  UUID orderId;
  String idemKey;
  String reason;
  long refundPaise;
  String refundMode;
  int coinsClawedBack;
  int coinsRestored;
  long couponClawbackPaise;
  UUID createdBy;
  Instant createdAt;

  /** For JPA. */
  protected OrderReturnEntity() {}
}
