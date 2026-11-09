package com.vijay.mall.wallet.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Table {@code coin_ledger}: every coin movement of every customer. An earning row doubles as a lot, with the coins
 * still unspent in {@code remaining} and an expiry; the idempotency key makes each movement happen only once.
 */
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

  /** Required by JPA; not for application code. */
  protected CoinLedgerEntity() {}
}
