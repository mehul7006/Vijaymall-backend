package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

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

  protected PosReturnItemEntity() {}
}
