package com.vijay.mall.wallet.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code wallet_balance}: creating and row-locking a balance, and the ledger consistency query.
 */
interface WalletJpa extends JpaRepository<WalletBalanceEntity, UUID> {
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "insert into wallet_balance (user_id, coins, version) values (:u, 0, 0) on conflict (user_id) do nothing", nativeQuery = true)
  int createIfAbsent(@Param("u") UUID userId);

  /** Row lock: two orders of one customer can never spend the same coins. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from WalletBalanceEntity w where w.userId = :u")
  Optional<WalletBalanceEntity> lockByUser(@Param("u") UUID userId);

  @Query("select b.userId, b.coins, coalesce(sum(l.coins), 0) from WalletBalanceEntity b "
      + "left join CoinLedgerEntity l on l.userId = b.userId group by b.userId, b.coins "
      + "having b.coins <> coalesce(sum(l.coins), 0)")
  List<Object[]> mismatches();
}
