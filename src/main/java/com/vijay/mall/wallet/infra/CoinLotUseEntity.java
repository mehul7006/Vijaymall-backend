package com.vijay.mall.wallet.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/** Which earned lots a redemption took coins from, so a cancelled order can put them back. */
@Entity
@Table(name = "coin_lot_use")
@IdClass(LotUseId.class)
class CoinLotUseEntity {
  @Id UUID redeemId;
  @Id UUID lotId;
  int coins;

  protected CoinLotUseEntity() {}
}
