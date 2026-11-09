package com.vijay.mall.wallet.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

/**
 * Table {@code wallet_balance}: one row per customer holding the current coin balance, versioned for optimistic locking.
 */
@Entity
@Table(name = "wallet_balance")
class WalletBalanceEntity {
  @Id UUID userId;
  int coins;
  @Version long version;

  /** Required by JPA; not for application code. */
  protected WalletBalanceEntity() {}
}
