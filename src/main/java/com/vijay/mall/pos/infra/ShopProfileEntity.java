package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

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

  protected ShopProfileEntity() {}
}
