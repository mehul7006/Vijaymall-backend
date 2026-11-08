package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pos_shift")
class PosShiftEntity extends BaseEntity {
  UUID cashierId;
  Instant openedAt;
  long openingFloatPaise;
  Instant closedAt;
  Long expectedCashPaise;
  Long countedCashPaise;
  Long variancePaise;
  String note;

  protected PosShiftEntity() {}
}
