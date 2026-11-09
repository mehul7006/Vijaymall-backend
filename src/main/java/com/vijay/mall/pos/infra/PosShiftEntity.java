package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code pos_shift}: a cashier's cash-drawer shift, with the opening float and, once closed, the expected
 * and counted cash.
 */
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

  /** Required by JPA; not for application code. */
  protected PosShiftEntity() {}
}
