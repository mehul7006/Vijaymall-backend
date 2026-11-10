package com.vijay.mall.wallet.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code coin_rule} rows. */
interface RuleJpa extends JpaRepository<CoinRuleEntity, UUID> {
  List<CoinRuleEntity> findAllByOrderByNameAsc();

  /** Switches every active rule except {@code keep} off in one statement; returns how many were switched off. */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("update CoinRuleEntity e set e.active = false, e.updatedAt = current_timestamp where e.active = true and e.id <> :keep")
  int deactivateExcept(@Param("keep") UUID keep);

  /** Takes the transaction-long lock that makes activations run one after another. */
  @Query(value = "select count(*) from (select pg_advisory_xact_lock(7742002)) as locked", nativeQuery = true)
  long lockActivation();
}
