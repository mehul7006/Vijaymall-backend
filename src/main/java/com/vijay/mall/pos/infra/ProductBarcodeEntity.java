package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A row of {@code product_barcode}: one barcode (the key) and the product it identifies. */
@Entity
@Table(name = "product_barcode")
class ProductBarcodeEntity {
  @Id String code;
  UUID productId;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected ProductBarcodeEntity() {}
}
