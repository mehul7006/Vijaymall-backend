package com.vijay.mall.pos.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A row of {@code pos_sale}: a counter bill, with its invoice number, amounts, GST split and the idempotency key it
 * was created with.
 */
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
  long manualDiscountPaise;
  long roundOffPaise;
  String manualDiscountReason;
  UUID manualDiscountApprover;
  long totalPaise;
  long taxablePaise;
  long cgstPaise;
  long sgstPaise;
  int coinsEarned;
  Long tenderedPaise;
  long changePaise;
  String paymentType;
  String status;
  Instant createdAt;

  /** Required by JPA; not for application code. */
  protected PosSaleEntity() {}
}
