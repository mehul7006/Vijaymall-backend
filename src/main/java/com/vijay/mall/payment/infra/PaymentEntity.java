package com.vijay.mall.payment.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment")
class PaymentEntity extends BaseEntity {
  UUID orderId;
  String provider;
  String method;
  long amountPaise;
  String currency = "INR";
  String status;
  String providerOrderId;
  String providerRef;
  String failureReason;
  Instant expiresAt;
  Instant createdAt;
  Instant updatedAt;

  protected PaymentEntity() {}
}
