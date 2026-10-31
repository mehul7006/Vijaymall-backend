package com.vijay.mall.payment.infra;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentJpa extends JpaRepository<PaymentEntity, UUID> {
  Optional<PaymentEntity> findByProviderAndProviderOrderId(String provider, String providerOrderId);

  Optional<PaymentEntity> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);

  @Query("select p from PaymentEntity p where p.status = 'CREATED' and p.createdAt < :before order by p.createdAt")
  List<PaymentEntity> staleCreated(@Param("before") Instant before, Pageable page);
}
