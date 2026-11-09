package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A row of {@code pos_return_item}: one returned line of a credit note. */
@Entity
@Table(name = "pos_return_item")
class PosReturnItemEntity extends BaseEntity {
  UUID returnId;
  UUID saleItemId;
  int qty;
  long refundPaise;
  long taxablePaise;
  long cgstPaise;
  long sgstPaise;

  /** Required by JPA; not for application code. */
  protected PosReturnItemEntity() {}
}
