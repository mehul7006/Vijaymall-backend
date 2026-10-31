package com.vijay.mall.wallet.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A single row (id = 1) holding the shop-wide coin settings. */
@Entity
@Table(name = "coin_setting")
class CoinSettingEntity {
  @Id int id;
  long paisePerCoin;
  int maxRedeemPercent;
  int expiryMonths;

  protected CoinSettingEntity() {}
}
