package com.vijay.mall.payment.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Row of {@code payment_setting}: the on/off switch and display order of one payment method. */
@Entity
@Table(name = "payment_setting")
class PaymentSettingEntity {
  @Id String code;
  boolean enabled;
  int sortOrder;

  /** For JPA. */
  protected PaymentSettingEntity() {}
}
