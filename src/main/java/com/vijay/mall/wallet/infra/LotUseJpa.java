package com.vijay.mall.wallet.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code coin_lot_use} rows: which lots a redemption took coins from. */
interface LotUseJpa extends JpaRepository<CoinLotUseEntity, LotUseId> {
  List<CoinLotUseEntity> findByRedeemId(UUID redeemId);
}
