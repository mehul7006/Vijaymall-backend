package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "pos_payment")
class PosPaymentEntity extends BaseEntity {
  UUID saleId;
  String mode;
  long amountPaise;
  String reference;

  protected PosPaymentEntity() {}
}
