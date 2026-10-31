package com.vijay.mall.wallet.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "wallet_balance")
class WalletBalanceEntity {
  @Id UUID userId;
  int coins;
  @Version long version;

  protected WalletBalanceEntity() {}
}
