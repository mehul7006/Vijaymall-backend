package com.vijay.mall.payment.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port every settlement source implements: a payment gateway's settlement API, or the generic CSV importer. The rest of the app only
 * knows this interface, so a new brand (Paytm, Cashfree...) is one new class and no other change. All money is in paise.
 */
public interface SettlementProvider {
  /** What kind of line a settlement item is. */
  enum ItemKind { PAYMENT, REFUND, ADJUSTMENT, FEE }

  /**
   * A settlement as listed.
   * @param id the provider's settlement id
   * @param utr the bank reference of the payout, if known
   * @param grossPaise payments minus refunds before charges
   * @param feePaise the provider's fee without tax
   * @param taxPaise the tax charged on the fee
   * @param netPaise what reached the bank
   * @param status the provider's word, for example {@code processed}
   * @param settledAt when the payout was made
   */
  record Summary(String id, String utr, long grossPaise, long feePaise, long taxPaise, long netPaise, String status, Instant settledAt) {}

  /**
   * One line inside a settlement.
   * @param entityId the provider's id of this line (its settlement-entry id, or the payment or refund id)
   * @param kind the kind of line
   * @param paymentRef the provider's payment id (for PAYMENT and REFUND lines); null for adjustments and fees
   * @param refundRef the provider's refund id (REFUND lines)
   * @param grossPaise the amount before charges (always positive; a REFUND takes it away)
   * @param feePaise fee without tax
   * @param taxPaise tax on the fee
   * @param netPaise the amount this line moved
   * @param method {@code upi}, {@code card}, {@code netbanking}, ... when known
   * @param reference the cashier-visible reference (UPI ref, approval code, RRN) for imported rows; null otherwise
   */
  record Item(String entityId, ItemKind kind, String paymentRef, String refundRef, long grossPaise, long feePaise, long taxPaise,
              long netPaise, String method, Instant settledAt, String utr, String reference) {}

  /** A settlement with its lines. */
  record Detail(Summary summary, List<Item> items) {}

  /**
   * One page of the settlement list.
   * @param nextCursor pass it back for the next page; null when there are no more
   */
  record Listing(List<Summary> items, String nextCursor) {}

  /**
   * What the provider knows about one payment.
   * @param instrument {@code upi}, {@code card}, {@code netbanking}, {@code wallet}...
   * @param upiVpa the payer's UPI id
   * @param cardNetwork Visa, MasterCard, RuPay...; never a full card number
   * @param cardLast4 the last four digits only
   * @param bank the payer's bank
   * @param feePaise fee without tax, null when the provider has not computed it yet
   * @param taxPaise tax on the fee
   */
  record PaymentDetail(String instrument, String upiVpa, String cardNetwork, String cardLast4, String bank, Long feePaise, Long taxPaise) {}

  /** Short lowercase name that matches {@link PaymentGateway#provider()} of the same brand, e.g. "razorpay". */
  String provider();

  /** False when the provider's keys are not set, so it cannot be asked anything. */
  boolean configured();

  /** True when the daily job should pull from this provider. The CSV importer is fed by a person and returns false. */
  default boolean pulls() { return true; }

  /**
   * Settlements created in {@code [from, to)}, one page at a time.
   * @param cursor null for the first page, else the {@code nextCursor} of the previous one
   */
  Listing listSettlements(Instant from, Instant to, String cursor);

  /** One settlement with every payment, refund and adjustment inside; empty when the provider does not know it. */
  Optional<Detail> settlementDetail(String settlementId);

  /** How a payment was paid and what it cost; empty when the provider does not know it or does not give details. */
  Optional<PaymentDetail> fetchPaymentDetail(String providerRef);
}
