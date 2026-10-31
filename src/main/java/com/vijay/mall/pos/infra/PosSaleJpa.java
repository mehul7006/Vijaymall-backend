package com.vijay.mall.pos.infra;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PosSaleJpa extends JpaRepository<PosSaleEntity, UUID> {
  Optional<PosSaleEntity> findByIdemKey(String idemKey);

  Optional<PosSaleEntity> findByInvoiceNo(String invoiceNo);

  List<PosSaleEntity> findTop50ByCustomerMobileOrderByCreatedAtDesc(String mobile);

  List<PosSaleEntity> findTop200ByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(Instant from, Instant to);

  /** Serialises two returns against one bill. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select s from PosSaleEntity s where s.id = :id")
  Optional<PosSaleEntity> lockById(@Param("id") UUID id);
}
