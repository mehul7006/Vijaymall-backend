package com.vijay.mall.purchasing.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the {@code purchase} table (supplier deliveries). */
interface PurchaseJpa extends JpaRepository<PurchaseEntity, UUID> {
  /** The delivery that was recorded under this idempotency key, if any. */
  Optional<PurchaseEntity> findByIdemKey(String idemKey);

  /** One page of deliveries, newest first. */
  Page<PurchaseEntity> findAllByOrderByCreatedAtDesc(Pageable page);
}
