package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A row of {@code pos_payment}: one payment part (mode, amount, reference) of a counter bill. */
@Entity
@Table(name = "pos_payment")
class PosPaymentEntity extends BaseEntity {
  UUID saleId;
  String mode;
  long amountPaise;
  String reference;

  /** Required by JPA; not for application code. */
  protected PosPaymentEntity() {}
}
