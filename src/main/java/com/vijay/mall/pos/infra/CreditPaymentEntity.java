package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code credit_payment}: one repayment a customer made against their credit, with its receipt number and
 * the idempotency key that makes a retry safe.
 */
@Entity
@Table(name = "credit_payment")
class CreditPaymentEntity extends BaseEntity {
  String receiptNo;
  String idemKey;
  UUID userId;
  UUID cashierId;
  long amountPaise;
  String note;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected CreditPaymentEntity() {}
}
