package com.vijay.mall.pos.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code pos_payment}, the payment parts of counter bills. */
interface PosPaymentJpa extends JpaRepository<PosPaymentEntity, UUID> {
  List<PosPaymentEntity> findBySaleIdIn(Collection<UUID> saleIds);

  /** Stores the payer detail of a line as JSON. */
  @Modifying
  @Query(value = "update pos_payment set detail = cast(:detail as jsonb) where id = :id", nativeQuery = true)
  int setDetail(@Param("id") UUID id, @Param("detail") String detail);
}
