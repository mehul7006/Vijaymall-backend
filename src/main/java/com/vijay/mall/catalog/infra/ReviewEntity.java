package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_review")
class ReviewEntity extends BaseEntity {
  UUID productId;
  UUID userId;
  UUID orderId;
  int rating;
  String comment;
  Instant createdAt;
  Instant updatedAt;

  protected ReviewEntity() {}
}
