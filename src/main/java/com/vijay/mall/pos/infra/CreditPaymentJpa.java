package com.vijay.mall.pos.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CreditPaymentJpa extends JpaRepository<CreditPaymentEntity, UUID> {
  Optional<CreditPaymentEntity> findByIdemKey(String idemKey);
}
