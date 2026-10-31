package com.vijay.mall.payment.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentEventJpa extends JpaRepository<PaymentEventEntity, UUID> {
  boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
