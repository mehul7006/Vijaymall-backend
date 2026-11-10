package com.vijay.mall.wallet.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code coin_ledger}: open lots for spending, idempotency-key lookups, history pages and expired lots.
 */
interface LedgerJpa extends JpaRepository<CoinLedgerEntity, UUID> {
  /** Open lots, soonest expiry first, locked while an order spends from them. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from CoinLedgerEntity l where l.userId = :u and l.remaining > 0 and (l.expiresAt is null or l.expiresAt > :now) "
      + "order by l.expiresAt asc nulls last, l.createdAt asc")
  List<CoinLedgerEntity> openLotsForUpdate(@Param("u") UUID userId, @Param("now") Instant now);

  /** Coins left in the user's open lots, without locking them (for previews). */
  @Query("select coalesce(sum(l.remaining), 0) from CoinLedgerEntity l where l.userId = :u and l.remaining > 0 "
      + "and (l.expiresAt is null or l.expiresAt > :now)")
  long openCoins(@Param("u") UUID userId, @Param("now") Instant now);

  boolean existsByIdemKey(String idemKey);

  Optional<CoinLedgerEntity> findByIdemKey(String idemKey);

  Page<CoinLedgerEntity> findByUserId(UUID userId, Pageable page);

  @Query("select l from CoinLedgerEntity l where l.remaining > 0 and l.expiresAt is not null and l.expiresAt <= :now order by l.expiresAt")
  List<CoinLedgerEntity> expired(@Param("now") Instant now, Pageable page);

  @Query("select l from CoinLedgerEntity l where l.id in :ids")
  List<CoinLedgerEntity> byIds(@Param("ids") Collection<UUID> ids);
}
