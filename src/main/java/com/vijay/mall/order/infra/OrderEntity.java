package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orders")
class OrderEntity extends BaseEntity {
  String orderNo;
  UUID userId;
  String idemKey;
  String fulfilment;
  String status;
  long subtotalPaise;
  String couponCode;
  long couponDiscountPaise;
  int coinsUsed;
  long coinDiscountPaise;
  long deliveryFeePaise;
  long totalPaise;
  int coinsEarned;
  boolean coinsCredited;
  boolean stockReserved;
  String paymentMethod;
  String paymentStatus;
  String addressSnapshot;
  String deliverySlot;
  String cancelReason;
  Instant expiresAt;
  Instant placedAt;
  Instant updatedAt;
  @Version Long version;

  protected OrderEntity() {}
}
