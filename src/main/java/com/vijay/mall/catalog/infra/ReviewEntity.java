package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity for the {@code product_review} table: one customer's star rating and comment for a product, with the delivered order it was written for.
 */
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

  /** No-argument constructor required by JPA; not for application code. */
  protected ReviewEntity() {}
}
