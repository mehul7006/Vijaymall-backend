package com.vijay.mall.payment.paytm.infra;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code terminal_payment}. */
public interface TerminalPaymentJpa extends JpaRepository<TerminalPaymentEntity, UUID> {
  /** The payment started with this idempotency key. */
  Optional<TerminalPaymentEntity> findByIdemKey(String idemKey);

  /** The open payment of a machine, if any. */
  Optional<TerminalPaymentEntity> findFirstByPaymentTerminalIdAndStatusIn(UUID paymentTerminalId, Collection<String> statuses);

  /** Locks one payment for update. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from TerminalPaymentEntity p where p.id = :id")
  Optional<TerminalPaymentEntity> lockById(@Param("id") UUID id);

  /**
   * Payments due for a check, locked and skipping rows another server holds (SKIP LOCKED = lock timeout -2). Open payments, and
   * expired ones still inside the late-payment watch, whose {@code nextPollAt} has passed.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select p from TerminalPaymentEntity p where p.status in :statuses and p.nextPollAt is not null and p.nextPollAt <= :now order by p.nextPollAt")
  List<TerminalPaymentEntity> due(@Param("statuses") Collection<String> statuses, @Param("now") Instant now, Pageable page);

  /** The same for one payment (a till that polls). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select p from TerminalPaymentEntity p where p.id = :id and p.status in :statuses and p.nextPollAt is not null and p.nextPollAt <= :now")
  Optional<TerminalPaymentEntity> dueById(@Param("id") UUID id, @Param("statuses") Collection<String> statuses, @Param("now") Instant now);
}
