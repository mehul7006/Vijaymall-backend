package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

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

  protected PurchaseEntity() {}
}
