package com.vijay.mall.payment.paytm.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The result codes Paytm documents for the wireless (cloud ECR) APIs, each with a stable internal code and a plain-English message
 * that can be shown to a cashier. Codes Paytm adds later are handled by {@link #of}, which keeps the raw code.
 */
public enum PaytmResultCode {
  /** The sale request reached the machine. */
  ACCEPTED("0009", Outcome.ACCEPTED, "ACCEPTED", "Paytm accepted the request."),
  /** The payment was taken. */
  SUCCESS("0000", Outcome.SUCCESS, "SUCCESS", "Payment received."),
  /** Waiting for the customer on the machine. */
  PENDING_A("0010", Outcome.PENDING, "PENDING", "Waiting for the customer to pay on the machine."),
  /** Waiting for the customer on the machine (second pending code). */
  PENDING_B("0030", Outcome.PENDING, "PENDING", "Waiting for the customer to pay on the machine."),
  /** The payment failed or was cancelled on the machine. */
  FAILED("0011", Outcome.FAILED, "PAYMENT_FAILED", "The payment failed or was cancelled on the machine."),
  /** Paytm has no such transaction. */
  NOT_FOUND("0404", Outcome.NOT_FOUND, "NOT_FOUND", "Paytm has no payment with this id."),
  /** Paytm asks to ask again later. */
  RETRY("0012", Outcome.RETRY, "RETRY_LATER", "Paytm asked to check again in a moment."),
  /** The machine is switched off or not active for wireless payments. */
  TERMINAL_INACTIVE("0007", Outcome.REJECTED, "TERMINAL_INACTIVE", "The card machine is not active or not online. Switch it on and check its network."),
  /** The machine already holds an unfinished request. */
  TERMINAL_BUSY("0333", Outcome.REJECTED, "TERMINAL_BUSY_AT_PAYTM", "The card machine already has another payment open. Finish or cancel it on the machine first."),
  /** The transaction id was used before. */
  DUPLICATE("0233", Outcome.DUPLICATE, "DUPLICATE_TRANSACTION", "Paytm already has a payment with this id."),
  /** The checksum did not match. */
  BAD_CHECKSUM("0330", Outcome.REJECTED, "BAD_CHECKSUM", "Paytm rejected the signature: the merchant key or merchant id saved in the configuration is wrong."),
  /** A void was already requested for this payment. */
  MULTIPLE_VOIDS("0182", Outcome.REJECTED, "VOID_ALREADY_REQUESTED", "A void was already requested for this payment.");

  /** What a code means for the till. */
  public enum Outcome {
    /** The request was taken. */ ACCEPTED,
    /** Money received. */ SUCCESS,
    /** Still open. */ PENDING,
    /** Finished without money. */ FAILED,
    /** Unknown transaction. */ NOT_FOUND,
    /** Check again later. */ RETRY,
    /** The request was refused. */ REJECTED,
    /** The id already exists at Paytm. */ DUPLICATE,
    /** A code this software does not know. */ ERROR,
    /** No usable answer (network down, timeout, unreadable). */ NETWORK_ERROR
  }

  /** The code as Paytm sends it. */
  public final String code;
  /** What the code means. */
  public final Outcome outcome;
  /** Stable code the apps can switch on. */
  public final String internalCode;
  /** Plain-English explanation. */
  public final String message;

  PaytmResultCode(String code, Outcome outcome, String internalCode, String message) {
    this.code = code;
    this.outcome = outcome;
    this.internalCode = internalCode;
    this.message = message;
  }

  private static final Map<String, PaytmResultCode> BY_CODE = Arrays.stream(values()).collect(Collectors.toMap(c -> c.code, Function.identity()));

  /** The documented code, or empty when Paytm sent something this version does not know. */
  public static Optional<PaytmResultCode> of(String code) { return Optional.ofNullable(BY_CODE.get(code)); }
}
