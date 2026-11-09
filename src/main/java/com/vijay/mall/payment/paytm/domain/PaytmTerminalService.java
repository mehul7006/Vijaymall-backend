package com.vijay.mall.payment.paytm.domain;

/**
 * What the till needs from a Paytm wireless card machine. Small and vendor-shaped on purpose: a thin adapter maps it to the generic
 * card-terminal port (see docs/frontend/73-paytm-terminal.md).
 */
public interface PaytmTerminalService {
  /**
   * A sale to send.
   * @param terminalId the machine's Paytm terminal id (paytmTid)
   * @param merchantTransactionId 8 to 32 letters and digits, unique
   * @param transactionDateTime India time {@code yyyy-MM-dd HH:mm:ss}; the same value must be used for status and void
   * @param amountPaise amount in paise
   * @param reference bill number or cart reference, shown on the machine and kept as the merchant reference
   */
  record Sale(String terminalId, String merchantTransactionId, String transactionDateTime, long amountPaise, String reference) {}

  /**
   * Identifies an earlier sale.
   * @param terminalId the machine's Paytm terminal id
   * @param merchantTransactionId the id used for the sale
   * @param transactionDateTime the date and time sent with the sale
   */
  record Txn(String terminalId, String merchantTransactionId, String transactionDateTime) {}

  /**
   * Outcome of a cancel attempt.
   * @param cancelled true only when Paytm reports the sale ended without payment
   * @param reply the status answer the decision is based on
   */
  record CancelResult(boolean cancelled, PaytmReply reply) {}

  /**
   * Result of the admin's test button.
   * @param ok whether the signature, merchant id and terminal were accepted
   * @param code stable code such as {@code OK}, {@code TERMINAL_INACTIVE}, {@code BAD_CREDENTIALS}, {@code NETWORK}
   * @param message plain explanation
   */
  record ConnectionResult(boolean ok, String code, String message) {}

  /** Sends the sale to the machine. Never retried: a lost answer is resolved with {@link #status}. */
  PaytmReply requestSale(Sale sale);

  /** Reads the result of a sale. Retried a couple of times when the network fails. */
  PaytmReply status(Txn txn);

  /**
   * Paytm documents no call that cancels a sale waiting on the machine, so this only checks the status: {@code cancelled} is true
   * when Paytm reports the sale ended without payment (the cashier cancelled on the machine).
   */
  CancelResult cancelPending(Txn txn);

  /** Voids a paid sale of the same day. */
  PaytmReply voidSale(Txn txn);

  /** Sends a status call for a made-up transaction id: proves signature, merchant id and terminal without moving money. Never throws. */
  ConnectionResult testConnection(String terminalId);
}
