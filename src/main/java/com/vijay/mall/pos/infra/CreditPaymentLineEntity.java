package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "credit_payment_line")
class CreditPaymentLineEntity extends BaseEntity {
  UUID paymentId;
  String mode;
  long amountPaise;
  String reference;

  protected CreditPaymentLineEntity() {}
}
