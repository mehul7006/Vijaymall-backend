package com.vijay.mall.payment.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for a card or UPI machine at the counter. The till asks it to collect an amount, then asks how it went. A brand adapter
 * (for example one for a Paytm machine) implements this and nothing else changes; {@code ManualTerminal} needs no vendor API.
 */
public interface CardTerminal {
  /** Where the payment stands. */
  enum State { PENDING, APPROVED, DECLINED, CANCELLED, EXPIRED }

  /** How the cashier completes it: {@code MANUAL} (on the machine, then type the code), {@code QR} (customer scans), {@code PUSH} (the machine prompts itself). */
  enum Mode { MANUAL, QR, PUSH }

  /**
   * A payment session.
   * @param id the adapter's own durable id when it keeps sessions itself (a Paytm machine payment), else null: the till then holds the
   *     session row and {@code reference} is the key
   * @param reference our reference, passed to {@link #requestPayment}
   * @param instructions what the cashier should do now
   * @param qrPayload the text to draw as a QR, when the brand supports showing one
   * @param qrImageUrl an image of the QR, when the brand serves one
   * @param approvalCode the bank approval code once approved
   * @param rrn the retrieval reference number once approved
   * @param detail what an adapter that keeps its own sessions adds (machine, card, states); null for the simple adapters
   */
  record Session(UUID id, String reference, State state, Mode mode, String instructions, String qrPayload, String qrImageUrl, String approvalCode,
                 String rrn, Detail detail) {
    /** A session of an adapter that keeps no session of its own. */
    public Session(String reference, State state, Mode mode, String instructions, String qrPayload, String qrImageUrl, String approvalCode, String rrn) {
      this(null, reference, state, mode, instructions, qrPayload, qrImageUrl, approvalCode, rrn, null);
    }
  }

  /**
   * The extra facts of a session an adapter keeps itself, all shown to the cashier. Nothing here is a full card number.
   * @param amountPaise the amount asked
   * @param moneyAccountId the account the machine's money lands in
   * @param providerStatus the vendor's own status word (for a Paytm machine REQUESTED, PENDING, UNKNOWN, SUCCESS, FAILED, CANCELLED, EXPIRED, VOIDED)
   * @param message plain text for the cashier
   * @param terminalId the vendor's terminal id
   * @param terminalName the name of the machine at the counter
   * @param payMethod how the customer paid once paid: CREDIT_CARD, DEBIT_CARD, UPI...
   * @param cardScheme card network
   * @param maskedCard card number as masked by the vendor
   * @param issuingBank the customer's bank
   * @param upiVpa the payer's UPI address, masked
   * @param canCancel true while a cancel can be tried
   * @param cancelOnMachine true when the cashier asked to cancel and must now press cancel on the machine
   * @param canVoid true when a same-day void is possible
   * @param pollAfterSeconds how long the till waits before asking again
   * @param posSaleId the bill that used the payment, or null
   * @param createdAt when it started
   * @param finishedAt when it reached a final state, or null
   */
  record Detail(long amountPaise, UUID moneyAccountId, String providerStatus, String message, String terminalId, String terminalName, String payMethod,
                String cardScheme, String maskedCard, String issuingBank, String upiVpa, boolean canCancel, boolean cancelOnMachine, boolean canVoid,
                int pollAfterSeconds, UUID posSaleId, Instant createdAt, Instant finishedAt) {}

  /**
   * What the till chose when it started a payment.
   * @param actor who is at the till
   * @param amountPaise the amount in paise
   * @param reference the till's reference, unique per payment attempt
   * @param moneyAccountId the account of the chosen channel, or null
   * @param terminalId the channel's terminal id, or null
   */
  record Request(UUID actor, long amountPaise, String reference, UUID moneyAccountId, String terminalId) {}

  /**
   * A paid session used on a counter bill.
   * @param reference what to store in the bill line's reference (the RRN when there is one)
   * @param moneyAccountId the account the money went to, or null to let the shop default apply
   * @param terminalId the machine's terminal id, or null
   * @param detail payer facts kept on the bill line, all masked
   */
  record Attachment(String reference, UUID moneyAccountId, String terminalId, Map<String, String> detail) {}

  /** Short lowercase name used in the {@code pos.terminalProvider} setting, e.g. "manual". */
  String provider();

  /** False when the machine's credentials are not set. */
  default boolean configured() { return true; }

  /** True when the cashier confirms the result by hand (types the approval code); false when {@link #status} reports it. */
  default boolean manualConfirmation() { return false; }

  /** Asks the machine to collect {@code amountPaise}; {@code reference} is unique per request. */
  Session requestPayment(long amountPaise, String reference);

  /** What happened so far. */
  Session status(String reference);

  /** Cancels a payment that is still pending. */
  Session cancel(String reference);

  /** Starts a payment on the channel the till chose. Adapters that need the machine override it; the others ignore the channel. */
  default Session requestPayment(Request r) { return requestPayment(r.amountPaise(), r.reference()); }

  /** The session an adapter keeps itself (a Paytm machine payment) by its id, refreshed from the machine when due; empty for any other id. */
  default Optional<Session> find(UUID sessionId) { return Optional.empty(); }

  /** Cancels a session this adapter keeps itself. Only called for ids {@link #find} answered. */
  default Session cancel(UUID actor, UUID sessionId) { throw new UnsupportedOperationException("this terminal keeps no sessions of its own"); }

  /** Voids a paid session this adapter keeps itself, when the machine allows it. Only called for ids {@link #find} answered. */
  default Session voidPayment(UUID actor, UUID sessionId) { throw new UnsupportedOperationException("this terminal keeps no sessions of its own"); }

  /** Uses a paid session this adapter keeps itself on a bill, inside the bill's transaction; empty when the id is not one of its sessions. */
  default Optional<Attachment> attach(UUID sessionId, UUID saleId, long amountPaise, String mode, String field, UUID actor) { return Optional.empty(); }
}
