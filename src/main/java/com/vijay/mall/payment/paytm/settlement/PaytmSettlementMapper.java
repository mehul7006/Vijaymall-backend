package com.vijay.mall.payment.paytm.settlement;

import com.vijay.mall.payment.domain.SettlementProvider.Item;
import com.vijay.mall.payment.domain.SettlementProvider.ItemKind;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a normalized Paytm line into the bank module's settlement item, the same way for the dashboard CSV and the settlement API.
 * A sale becomes a PAYMENT matched to our payments by order id or RRN; a refund, chargeback or anything else becomes an ADJUSTMENT that
 * takes money away. Commission is the fee and GST is the tax on it.
 */
final class PaytmSettlementMapper {
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private PaytmSettlementMapper() {}

  /** True for a line Paytm paid out: failed, pending and cancelled lines moved no money and are left out. */
  static boolean paidOut(PaytmSettlementRecord r) {
    String s = r.status() == null ? "" : r.status().toUpperCase(Locale.ROOT);
    return !(s.contains("FAIL") || s.contains("PEND") || s.contains("CANCEL"));
  }

  private static long abs(Long v) { return v == null ? 0 : Math.abs(v); }

  /** The settlement item for a line. */
  static Item toItem(PaytmSettlementRecord r) {
    boolean sale = "ACQUIRING".equals(r.transactionType()) || r.transactionType() == null;
    long gross = r.amountPaise() != null ? Math.abs(r.amountPaise()) : abs(r.settledAmountPaise());
    long fee = abs(r.commissionPaise());
    long tax = abs(r.gstPaise());
    long net = sale ? (r.settledAmountPaise() != null ? r.settledAmountPaise() : gross - fee - tax) : -(r.settledAmountPaise() != null ? abs(r.settledAmountPaise()) : gross);
    String method = r.paymentMode() == null || "OTHER".equals(r.paymentMode()) ? null : r.paymentMode().toLowerCase(Locale.ROOT);
    String reference = r.orderId() != null ? r.orderId() : r.rrn();
    String alt = r.orderId() != null ? r.rrn() : null;
    var settledAt = r.settledDate() != null ? r.settledDate().atStartOfDay(INDIA).toInstant()
        : r.transactionDate() != null ? r.transactionDate().atZone(INDIA).toInstant() : null;
    Map<String, String> detail = new LinkedHashMap<>();
    put(detail, "source", r.source());
    put(detail, "transactionId", r.transactionId());
    put(detail, "orderId", r.orderId());
    put(detail, "transactionType", r.transactionType());
    put(detail, "status", r.status());
    put(detail, "requestType", r.requestType());
    put(detail, "posId", r.posId());
    put(detail, "extSerialNo", r.extSerialNo());
    put(detail, "cardNetwork", r.cardNetwork());
    put(detail, "maskedCard", r.maskedCard());
    put(detail, "vpa", r.vpa());
    put(detail, "payoutId", r.payoutId());
    put(detail, "splitFlag", r.splitFlag());
    put(detail, "transactionDate", r.transactionDate() == null ? null : r.transactionDate().toString());
    if (r.detail() != null) r.detail().forEach((k, v) -> detail.putIfAbsent(k, v));
    return new Item(r.transactionId(), sale ? ItemKind.PAYMENT : ItemKind.ADJUSTMENT, null, null, gross, fee, tax, net, method, settledAt, r.utr(), reference,
        alt, detail);
  }

  private static void put(Map<String, String> m, String k, String v) {
    if (v != null && !v.isBlank()) m.put(k, v);
  }
}
