package com.vijay.mall.payment.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "payment_setting")
class PaymentSettingEntity {
  @Id String code;
  boolean enabled;
  int sortOrder;

  protected PaymentSettingEntity() {}
}
