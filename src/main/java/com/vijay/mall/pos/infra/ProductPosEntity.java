package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_pos")
class ProductPosEntity {
  @Id UUID productId;
  String soldBy;
  String plu;
  int gstPercent;
  String hsn;
  Instant updatedAt;

  protected ProductPosEntity() {}
}
