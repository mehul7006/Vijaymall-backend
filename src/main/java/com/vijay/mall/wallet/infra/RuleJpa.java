package com.vijay.mall.wallet.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RuleJpa extends JpaRepository<CoinRuleEntity, UUID> {
  List<CoinRuleEntity> findAllByOrderByNameAsc();
}
