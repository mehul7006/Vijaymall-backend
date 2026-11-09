package com.vijay.mall.catalog.infra;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA entity for the {@code unit_of_measure} table; the unit code is the primary key. */
@Entity
@Table(name = "unit_of_measure")
class UnitEntity {
  @Id String code;
  @Column(name = "name_en") String nameEn;
  @Column(name = "name_gu") String nameGu;
  @Column(name = "sold_by") String soldBy;
  @Column(name = "sort_order") int sortOrder;
  boolean active;

  /** No-argument constructor required by JPA; not for application code. */
  protected UnitEntity() {}
}
