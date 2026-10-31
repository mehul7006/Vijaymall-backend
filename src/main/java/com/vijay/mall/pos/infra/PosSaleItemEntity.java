package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "pos_sale_item")
class PosSaleItemEntity extends BaseEntity {
  UUID saleId;
  UUID productId;
  String name;
  String soldBy;
  int qty;
  long unitPricePaise;
  long linePaise;
  long discountPaise;
  long netPaise;
  int gstPercent;
  String hsn;
  long taxablePaise;
  long cgstPaise;
  long sgstPaise;
  int returnedQty;

  protected PosSaleItemEntity() {}
}
