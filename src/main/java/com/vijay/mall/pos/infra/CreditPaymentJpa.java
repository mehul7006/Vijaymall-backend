package com.vijay.mall.pos.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code credit_payment}; can find a repayment by the idempotency key it was taken with. */
interface CreditPaymentJpa extends JpaRepository<CreditPaymentEntity, UUID> {
  Optional<CreditPaymentEntity> findByIdemKey(String idemKey);
}
