package com.vijay.mall.payment.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One wrong-amount or duplicate online payment that was refunded automatically. */
@Entity
@Table(name = "payment_anomaly")
class PaymentAnomalyEntity extends BaseEntity {
  UUID paymentId;
  UUID orderId;
  String kind;
  long expectedPaise;
  long receivedPaise;
  String providerRef;
  Instant createdAt;

  /** For JPA. */
  protected PaymentAnomalyEntity() {}
}
