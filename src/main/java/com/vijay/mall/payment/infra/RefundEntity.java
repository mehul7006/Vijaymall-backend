package com.vijay.mall.payment.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refund")
class RefundEntity extends BaseEntity {
  UUID paymentId;
  long amountPaise;
  String status;
  String providerRef;
  String reason;
  Instant createdAt;

  protected RefundEntity() {}
}
