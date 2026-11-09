package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code pos_hold}: a basket a cashier parked, stored as JSON. */
@Entity
@Table(name = "pos_hold")
class PosHoldEntity extends BaseEntity {
  UUID cashierId;
  String label;
  String customerMobile;
  String payload;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected PosHoldEntity() {}
}
