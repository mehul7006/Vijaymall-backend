package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code product_pos}: how a product is sold at the counter (piece or weight), its scale item code, GST
 * percent and HSN.
 */
@Entity
@Table(name = "product_pos")
class ProductPosEntity {
  @Id UUID productId;
  String soldBy;
  String plu;
  int gstPercent;
  String hsn;
  Instant updatedAt;

  /** Required by JPA; not for application code. */
  protected ProductPosEntity() {}
}
