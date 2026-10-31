package com.vijay.mall.catalog.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "category")
class CategoryEntity extends BaseEntity {
  String nameEn;
  String nameGu;
  String imageUrl;
  int sortOrder;
  boolean active;

  protected CategoryEntity() {}
}
