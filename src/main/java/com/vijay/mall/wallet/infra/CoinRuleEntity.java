package com.vijay.mall.wallet.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coin_rule")
class CoinRuleEntity extends BaseEntity {
  String name;
  long spendPaise;
  long coins;
  String mode;
  long minOrderPaise;
  Instant startAt;
  Instant endAt;
  boolean active;

  protected CoinRuleEntity() {}
}
