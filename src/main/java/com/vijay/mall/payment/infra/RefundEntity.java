package com.vijay.mall.payment.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Row of the {@code refund} table: a refund in the queue ({@code PENDING}, {@code PROCESSING}, {@code ISSUED} or {@code FAILED}) with its attempt count and last error.
 */
@Entity
@Table(name = "refund")
class RefundEntity extends BaseEntity {
  UUID paymentId;
  long amountPaise;
  String status;
  String providerRef;
  /** The provider payment this refund gives back; null means the payment row's own reference. */
  String providerPaymentRef;
  String reason;
  Instant createdAt;
  int attempts;
  String lastError;
  Instant updatedAt;

  /** For JPA. */
  protected RefundEntity() {}
}
