package com.vijay.mall.payment.domain;

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
   * @param reference our reference, passed to {@link #requestPayment}
   * @param instructions what the cashier should do now
   * @param qrPayload the text to draw as a QR, when the brand supports showing one
   * @param qrImageUrl an image of the QR, when the brand serves one
   * @param approvalCode the bank approval code once approved
   * @param rrn the retrieval reference number once approved
   */
  record Session(String reference, State state, Mode mode, String instructions, String qrPayload, String qrImageUrl, String approvalCode,
                 String rrn) {}

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
}
