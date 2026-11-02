package com.vijay.mall.wishlist.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wishlist_item")
class WishlistItemEntity extends BaseEntity {
  UUID userId;
  UUID productId;
  Instant createdAt;

  protected WishlistItemEntity() {}
}
