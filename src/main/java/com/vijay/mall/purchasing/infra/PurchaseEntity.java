package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code purchase} table: the header of one supplier delivery (goods received note). The unique {@code idemKey} makes a retried receive harmless.
 */
@Entity
@Table(name = "purchase")
class PurchaseEntity extends BaseEntity {
  String supplierName;
  String invoiceNo;
  String note;
  long totalCostPaise;
  String idemKey;
  UUID createdBy;
  Instant createdAt;

  /** No-argument constructor required by JPA; not for application code. */
  protected PurchaseEntity() {}
}
