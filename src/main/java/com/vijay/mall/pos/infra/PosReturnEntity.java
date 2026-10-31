package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pos_return")
class PosReturnEntity extends BaseEntity {
  String creditNoteNo;
  String idemKey;
  UUID saleId;
  UUID cashierId;
  String reason;
  long refundPaise;
  long taxablePaise;
  long cgstPaise;
  long sgstPaise;
  int coinsClawedBack;
  int coinsRestored;
  Instant createdAt;

  protected PosReturnEntity() {}
}
