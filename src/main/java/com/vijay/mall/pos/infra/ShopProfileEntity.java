package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The single row (id 1) of {@code shop_profile}: logo, contact links, opening hours, holiday note and the
 * online-ordering switch.
 */
@Entity
@Table(name = "shop_profile")
class ShopProfileEntity {
  @Id int id;
  String logoUrl;
  String email;
  String whatsapp;
  String mapUrl;
  String website;
  String instagram;
  String facebook;
  String hours;
  String holidayNote;
  boolean onlineOrdersEnabled;
  String onlineOrdersMessage;
  Instant updatedAt;

  /** Required by JPA; not for application code. */
  protected ShopProfileEntity() {}
}
