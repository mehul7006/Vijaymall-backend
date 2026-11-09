package com.vijay.mall.wallet.infra;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key (redemption, lot) of {@code coin_lot_use}. */
class LotUseId implements Serializable {
  UUID redeemId;
  UUID lotId;

  LotUseId() {}

  LotUseId(UUID redeemId, UUID lotId) {
    this.redeemId = redeemId;
    this.lotId = lotId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof LotUseId other && Objects.equals(redeemId, other.redeemId) && Objects.equals(lotId, other.lotId);
  }

  @Override
  public int hashCode() { return Objects.hash(redeemId, lotId); }
}
