package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A row of {@code credit_payment_line}: one mode (cash, UPI or card) and amount of a split credit repayment. */
@Entity
@Table(name = "credit_payment_line")
class CreditPaymentLineEntity extends BaseEntity {
  UUID paymentId;
  String mode;
  long amountPaise;
  String reference;

  /** Required by JPA; not for application code. */
  protected CreditPaymentLineEntity() {}
}
