package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "credit_ledger")
class CreditLedgerEntity extends BaseEntity {
  UUID userId;
  String kind;
  long deltaPaise;
  long balanceAfterPaise;
  String refNo;
  UUID saleId;
  UUID returnId;
  UUID paymentId;
  String note;
  UUID actorId;
  Instant createdAt;

  protected CreditLedgerEntity() {}
}
