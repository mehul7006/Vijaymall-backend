package com.vijay.mall.pos.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code credit_payment_line}, the payment parts of credit repayments. */
interface CreditPaymentLineJpa extends JpaRepository<CreditPaymentLineEntity, UUID> {
  List<CreditPaymentLineEntity> findByPaymentIdIn(Collection<UUID> paymentIds);
}
