package com.vijay.mall.wishlist.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Table {@code wishlist_item}: one product a customer saved for later; a customer can save a product only once. */
@Entity
@Table(name = "wishlist_item")
class WishlistItemEntity extends BaseEntity {
  UUID userId;
  UUID productId;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected WishlistItemEntity() {}
}
