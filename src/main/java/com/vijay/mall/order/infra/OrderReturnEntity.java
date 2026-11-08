package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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
  UUID createdBy;
  Instant createdAt;

  protected OrderReturnEntity() {}
}
