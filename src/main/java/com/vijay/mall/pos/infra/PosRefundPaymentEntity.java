package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A row of {@code pos_refund_payment}: one payout part (mode, amount, reference) of a credit note. */
@Entity
@Table(name = "pos_refund_payment")
class PosRefundPaymentEntity extends BaseEntity {
  UUID returnId;
  String mode;
  long amountPaise;
  String reference;

  /** Required by JPA; not for application code. */
  protected PosRefundPaymentEntity() {}
}
