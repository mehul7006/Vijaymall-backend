package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * JPA entity for the {@code category} table: a product category with English and Gujarati names, an optional picture, a sort order and an active flag.
 */
@Entity
@Table(name = "category")
class CategoryEntity extends BaseEntity {
  String nameEn;
  String nameGu;
  String imageUrl;
  int sortOrder;
  boolean active;

  /** No-argument constructor required by JPA; not for application code. */
  protected CategoryEntity() {}
}
