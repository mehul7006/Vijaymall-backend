package com.vijay.mall.purchasing.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface PurchaseJpa extends JpaRepository<PurchaseEntity, UUID> {
  Optional<PurchaseEntity> findByIdemKey(String idemKey);

  Page<PurchaseEntity> findAllByOrderByCreatedAtDesc(Pageable page);
}
