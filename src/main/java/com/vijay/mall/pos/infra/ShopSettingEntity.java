package com.vijay.mall.pos.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A single row (id = 1) with the shop's details used on bills. */
@Entity
@Table(name = "shop_setting")
class ShopSettingEntity {
  @Id int id;
  String shopName;
  String address;
  String phone;
  String promise;
  String details;
  String gstin;
  String stateCode;
  String upiId;
  String invoicePrefix;
  String receiptFooter;
  String weighedPrefix;
  String weighedMode;
  Instant updatedAt;

  /** Required by JPA; not for application code. */
  protected ShopSettingEntity() {}
}
