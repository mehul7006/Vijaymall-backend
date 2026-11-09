package com.vijay.mall.purchasing.infra;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the {@code purchase_item} table (the lines of a delivery). */
interface PurchaseItemJpa extends JpaRepository<PurchaseItemEntity, UUID> {
  /** The lines of one delivery. */
  List<PurchaseItemEntity> findByPurchaseId(UUID purchaseId);
}
