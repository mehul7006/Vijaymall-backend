package com.vijay.mall.pos.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CreditJpa extends JpaRepository<CreditAccountEntity, UUID> {
  /** Serialises everything that changes one customer's balance. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from CreditAccountEntity a where a.userId = :id")
  Optional<CreditAccountEntity> lockById(@Param("id") UUID id);

  /** Makes sure the account row exists, whoever creates it first. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(value = "insert into credit_account (user_id, balance_paise, limit_paise, updated_at) values (:id, 0, 0, :now) "
      + "on conflict (user_id) do nothing", nativeQuery = true)
  int createIfAbsent(@Param("id") UUID userId, @Param("now") Instant now);
}
