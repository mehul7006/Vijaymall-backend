package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pos_hold")
class PosHoldEntity extends BaseEntity {
  UUID cashierId;
  String label;
  String customerMobile;
  String payload;
  Instant createdAt;

  protected PosHoldEntity() {}
}
