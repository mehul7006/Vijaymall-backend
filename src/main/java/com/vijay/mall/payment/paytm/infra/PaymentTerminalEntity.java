package com.vijay.mall.payment.paytm.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Row of {@code payment_terminal}: one card machine. {@code moneyAccountId} points at the bank module's account and has no foreign key. */
@Entity
@Table(name = "payment_terminal")
public class PaymentTerminalEntity extends BaseEntity {
  /** The vendor, {@code paytm}. */
  public String provider;
  /** The vendor's terminal id (paytmTid). */
  public String terminalId;
  /** Name the cashier sees, for example Counter 1. */
  public String name;
  /** Whether the machine can be picked at the till. */
  public boolean active;
  /** The money account the machine settles into, or null. */
  public UUID moneyAccountId;
  /** When it was added. */
  public Instant createdAt;
  /** When it was last changed. */
  public Instant updatedAt;

  /** For JPA. */
  protected PaymentTerminalEntity() {}

  /** Creates an empty row to fill. */
  public static PaymentTerminalEntity create() { return new PaymentTerminalEntity(); }
}
