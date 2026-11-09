package com.vijay.mall.payment.infra;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data access to {@code payment_event}; answers whether a provider event was already handled. */
interface PaymentEventJpa extends JpaRepository<PaymentEventEntity, UUID> {
  boolean existsByProviderAndProviderEventId(String provider, String providerEventId);
}
