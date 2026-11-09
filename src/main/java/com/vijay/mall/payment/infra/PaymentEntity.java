package com.vijay.mall.payment.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Row of the {@code payment} table: one online payment attempt for an order, with the provider's order and payment references and its status.
 */
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
  /** The money account that received the payment; null on rows from before accounts existed. */
  UUID moneyAccountId;
  /** When the provider reported the capture. */
  Instant capturedAt;
  Instant createdAt;
  Instant updatedAt;

  /** For JPA. */
  protected PaymentEntity() {}
}
