package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

  protected CreditPaymentEntity() {}
}
