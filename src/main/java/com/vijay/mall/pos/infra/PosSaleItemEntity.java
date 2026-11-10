package com.vijay.mall.pos.infra;

import java.math.BigDecimal;
import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** A row of {@code pos_sale_item}: one line of a counter bill, with its GST split and the units returned so far. */
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
  BigDecimal gstPercent;
  String hsn;
  long taxablePaise;
  long cgstPaise;
  long sgstPaise;
  int returnedQty;
  long couponSharePaise;
  /** Whole paise the units cost, from the stock lots they came out of; null for bills from before lots. */
  Long costPaise;

  /** Required by JPA; not for application code. */
  protected PosSaleItemEntity() {}
}
