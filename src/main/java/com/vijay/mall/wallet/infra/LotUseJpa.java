package com.vijay.mall.wallet.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface LotUseJpa extends JpaRepository<CoinLotUseEntity, LotUseId> {
  List<CoinLotUseEntity> findByRedeemId(UUID redeemId);
}
