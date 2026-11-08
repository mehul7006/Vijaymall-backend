package com.vijay.mall.purchasing.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface PurchaseItemJpa extends JpaRepository<PurchaseItemEntity, UUID> {
  List<PurchaseItemEntity> findByPurchaseId(UUID purchaseId);
}
