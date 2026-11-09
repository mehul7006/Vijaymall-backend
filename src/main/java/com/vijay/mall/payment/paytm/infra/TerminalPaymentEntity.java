package com.vijay.mall.payment.paytm.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Row of {@code terminal_payment}: one amount sent to a card machine and everything learned about it. */
@Entity
@Table(name = "terminal_payment")
public class TerminalPaymentEntity extends BaseEntity {
  /** The machine row. */
  public UUID paymentTerminalId;
  /** The vendor terminal id at the time. */
  public String terminalId;
  /** The vendor. */
  public String provider;
  /** Id sent to the vendor; 32 hex characters of {@link #id}. */
  public String merchantTransactionId;
  /** The date and time sent with the sale, India time; status and void must repeat it. */
  public String txnDateTime;
  /** The caller's idempotency key. */
  public String idemKey;
  /** Amount in paise. */
  public long amountPaise;
  /** Bill number or cart reference. */
  public String reference;
  /** REQUESTED, PENDING, SUCCESS, FAILED, CANCELLED, EXPIRED, UNKNOWN or VOIDED. */
  public String status;
  /** Last result code from the vendor. */
  public String resultCode;
  /** Plain message for the cashier. */
  public String message;
  /** CREDIT_CARD, DEBIT_CARD, UPI, EMI... */
  public String payMethod;
  /** Card network. */
  public String cardScheme;
  /** Masked card number as given by the vendor. */
  public String maskedCard;
  /** Retrieval reference number. */
  public String rrn;
  /** Authorisation code. */
  public String authCode;
  /** Card issuing bank. */
  public String issuingBank;
  /** Acquiring bank. */
  public String acquiringBank;
  /** Payer UPI address as given by the vendor. */
  public String upiVpa;
  /** Bank response code. */
  public String bankResponseCode;
  /** Bank response message. */
  public String bankResponseMessage;
  /** The vendor's last status body, cut to 4000 characters; holds only masked data. */
  public String rawResponse;
  /** The counter bill that used this payment. */
  public UUID posSaleId;
  /** The online order that used this payment, if any. */
  public UUID orderId;
  /** Who started it. */
  public UUID requestedBy;
  /** When a cashier asked to cancel it. */
  public Instant cancelRequestedAt;
  /** When a void was asked. */
  public Instant voidRequestedAt;
  /** When the void was accepted. */
  public Instant voidedAt;
  /** Not before this moment may it be checked again; also the lease that keeps two servers from checking at once. */
  public Instant nextPollAt;
  /** When it was last checked. */
  public Instant lastPolledAt;
  /** Creation time. */
  public Instant createdAt;
  /** Last change. */
  public Instant updatedAt;
  /** When it reached a final state. */
  public Instant finishedAt;

  /** For JPA. */
  protected TerminalPaymentEntity() {}

  /** Creates an empty row to fill. */
  public static TerminalPaymentEntity create() { return new TerminalPaymentEntity(); }
}
