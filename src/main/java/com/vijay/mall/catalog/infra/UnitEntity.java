package com.vijay.mall.catalog.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "unit_of_measure")
class UnitEntity {
  @Id String code;
  @Column(name = "name_en") String nameEn;
  @Column(name = "name_gu") String nameGu;
  @Column(name = "sold_by") String soldBy;
  @Column(name = "sort_order") int sortOrder;
  boolean active;

  protected UnitEntity() {}
}
