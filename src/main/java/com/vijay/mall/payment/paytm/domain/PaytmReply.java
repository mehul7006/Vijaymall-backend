package com.vijay.mall.payment.paytm.domain;

import com.vijay.mall.payment.paytm.domain.PaytmResultCode.Outcome;

/**
 * Typed answer of one Paytm call.
 *
 * @param outcome what the answer means
 * @param code Paytm's raw result code, or empty when there was no answer
 * @param internalCode stable code, for example {@code TERMINAL_INACTIVE}; {@code PAYTM_ERROR} for a code this version does not know
 * @param message plain-English text for the cashier or admin; never contains a secret
 * @param details transaction fields of a status answer, or {@code null}
 */
public record PaytmReply(Outcome outcome, String code, String internalCode, String message, TxnDetails details) {
  /** Whether Paytm answered at all. */
  public boolean answered() { return outcome != Outcome.NETWORK_ERROR; }

  /** Fields of a status answer. Every value may be null; card numbers are always masked by Paytm. */
  public record TxnDetails(String payMethod, String cardScheme, String maskedCard, String rrn, String authCode, String issuingBank,
                           String acquiringBank, String upiVpa, String bankResponseCode, String bankResponseMessage, Long amountPaise,
                           String raw) {}
}
