package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_barcode")
class ProductBarcodeEntity {
  @Id String code;
  UUID productId;
  Instant createdAt;

  protected ProductBarcodeEntity() {}
}
