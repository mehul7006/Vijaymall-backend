package com.vijay.mall.wallet.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code coin_rule} rows. */
interface RuleJpa extends JpaRepository<CoinRuleEntity, UUID> {
  List<CoinRuleEntity> findAllByOrderByNameAsc();
}
