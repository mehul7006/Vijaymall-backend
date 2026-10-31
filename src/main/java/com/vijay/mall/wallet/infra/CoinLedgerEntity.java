package com.vijay.mall.wallet.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coin_ledger")
class CoinLedgerEntity extends BaseEntity {
  UUID userId;
  String kind;
  int coins;
  Integer remaining;
  Instant expiresAt;
  UUID orderId;
  UUID ruleId;
  String idemKey;
  Instant createdAt;

  protected CoinLedgerEntity() {}
}
