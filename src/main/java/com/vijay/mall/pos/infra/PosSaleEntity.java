package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pos_sale")
class PosSaleEntity extends BaseEntity {
  String invoiceNo;
  String idemKey;
  UUID cashierId;
  UUID customerId;
  String customerMobile;
  long subtotalPaise;
  String couponCode;
  long couponDiscountPaise;
  int coinsUsed;
  long coinDiscountPaise;
  long totalPaise;
  long taxablePaise;
  long cgstPaise;
  long sgstPaise;
  int coinsEarned;
  Long tenderedPaise;
  long changePaise;
  String status;
  Instant createdAt;

  protected PosSaleEntity() {}
}
