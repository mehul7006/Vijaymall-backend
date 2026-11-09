package com.vijay.mall.payment.infra;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to the payment_anomaly table. */
interface PaymentAnomalyJpa extends JpaRepository<PaymentAnomalyEntity, UUID> {
  /** Newest first. */
  Page<PaymentAnomalyEntity> findAllByOrderByCreatedAtDesc(Pageable page);
}
