package com.vijay.mall.wallet.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Table {@code coin_rule}: an admin-defined rule that turns spending into coins, with its active window. */
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

  /** Required by JPA; not for application code. */
  protected CoinRuleEntity() {}
}
