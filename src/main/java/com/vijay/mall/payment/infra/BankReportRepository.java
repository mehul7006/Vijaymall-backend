package com.vijay.mall.payment.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries behind the bank and settlement reports. Every list is bounded by a date range or an index, reads one extra row to
 * know whether another page follows, and caps its count (see {@link BoundedCount}).
 */
@Repository
public class BankReportRepository {
  /** One line of the receipts report; {@code moneyAccountId} null is the "Unassigned" bucket. */
  public record ReceiptRow(UUID moneyAccountId, String accountName, String accountKind, String method, long receipts, long grossPaise,
                           long refundedPaise, long netPaise) {}

  /** A settlement in the list. */
  public record SettlementRow(UUID id, String provider, String providerSettlementId, String utr, long grossPaise, long feePaise, long taxPaise,
                              long netPaise, String status, Instant settledAt, UUID moneyAccountId, String accountName, String source, long items,
                              long unmatchedItems) {}

  /** A line inside a settlement with what it was matched to. */
  public record SettlementItemRow(UUID id, String kind, String providerEntityId, long grossPaise, long feePaise, long taxPaise, long netPaise,
                                  String method, Instant settledAt, String utr, String reference, boolean matched, UUID paymentId, UUID orderId,
                                  String orderNo, UUID refundId, UUID posPaymentId, String invoiceNo) {}

  /** A captured payment or counter card line with no settlement yet. {@code source} is ONLINE or COUNTER. */
  public record UnsettledRow(String source, UUID id, String reference, UUID moneyAccountId, String accountName, long amountPaise, Instant receivedAt) {}

  /** Fees of one month for one account. {@code missingFee} counts payments the provider has not reported a fee for yet. */
  public record FeeRow(String month, UUID moneyAccountId, String accountName, long payments, long grossPaise, long feePaise, long taxPaise,
                       long missingFee) {}

  /** A line of an account's book. */
  public record BookRow(Instant at, String type, String direction, long amountPaise, String sourceType, String sourceId, String description,
                        long balancePaise) {}

  /** How one online payment was made, for the admin order view. Never holds a card number. */
  public record PayerRow(UUID paymentId, String provider, String status, long amountPaise, String instrument, String upiVpa, String cardNetwork,
                         String cardLast4, String bank, Long feePaise, Long taxPaise, Long netPaise, String settlementRef, String utr,
                         Instant settledAt, UUID moneyAccountId, String accountName, String providerRef, Instant capturedAt) {}

  private final JdbcClient jdbc;

  BankReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Instant instant(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant t) { return t == null ? null : Timestamp.from(t); }

  private static Long nullableLong(ResultSet rs, String c) throws SQLException {
    long v = rs.getLong(c);
    return rs.wasNull() ? null : v;
  }

  // ---------- receipts ----------
  private static final String RECEIPT_EVENTS = "with ev as ("
      + "select p.money_account_id acc, upper(coalesce(p.instrument, 'ONLINE')) method, p.amount_paise gross, 0::bigint refunded, 1 cnt from payment p "
      + "where p.captured_at >= :f and p.captured_at < :t and p.status in ('CAPTURED','REFUNDED') "
      + "union all select p.money_account_id, p.mode, p.amount_paise, 0, 1 from pos_payment p join pos_sale s on s.id = p.sale_id "
      + "where s.created_at >= :f and s.created_at < :t and p.mode <> 'CREDIT' "
      + "union all select r.money_account_id, upper(coalesce(pay.instrument, 'ONLINE')), 0, r.amount_paise, 0 from refund r join payment pay on pay.id = r.payment_id "
      + "where r.created_at >= :f and r.created_at < :t and r.status <> 'FAILED' "
      + "union all select x.money_account_id, x.mode, 0, x.amount_paise, 0 from pos_refund_payment x join pos_return rt on rt.id = x.return_id "
      + "where rt.created_at >= :f and rt.created_at < :t) ";
  private static final String RECEIPT_GROUP = "select ev.acc, a.name, a.kind, ev.method, sum(ev.cnt) cnt, sum(ev.gross) gross, sum(ev.refunded) refunded "
      + "from ev left join money_account a on a.id = ev.acc group by ev.acc, a.name, a.kind, ev.method";

  /** Money received and paid back in {@code [from, to)} by account and method; money without an account is one "Unassigned" bucket per method. */
  public Page<ReceiptRow> receipts(Instant from, Instant to, int page, int size) {
    List<ReceiptRow> rows = jdbc.sql(RECEIPT_EVENTS + RECEIPT_GROUP + " order by a.name nulls last, ev.method limit :n offset :o")
        .param("f", ts(from)).param("t", ts(to)).param("n", size + 1).param("o", (long) page * size).query((rs, i) -> {
          long gross = rs.getLong("gross");
          long refunded = rs.getLong("refunded");
          UUID acc = rs.getObject("acc", UUID.class);
          return new ReceiptRow(acc, acc == null ? "Unassigned" : rs.getString("name"), rs.getString("kind"), rs.getString("method"), rs.getLong("cnt"),
              gross, refunded, gross - refunded);
        }).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql(RECEIPT_EVENTS + "select count(*) from (" + RECEIPT_GROUP + " limit "
        + (BoundedCount.CAP + 1) + ") g").param("f", ts(from)).param("t", ts(to)).query(Long.class).single());
  }

  // ---------- consolidated receipts ----------
  /** One normalized receipt row, from any vendor or channel. {@code source} is ONLINE_ORDER or POS_SALE; payer detail is masked. */
  public record ReceiptLine(String source, UUID id, String provider, UUID moneyAccountId, String accountName, String terminalId, String method,
                            String payer, String reference, String utr, long grossPaise, Long feePaise, Long taxPaise, Long netPaise, String status,
                            String settlementRef, Instant at, String documentNo) {}

  /** Totals of one vendor, account and method. */
  public record ReceiptTotal(String provider, UUID moneyAccountId, String accountName, String method, long receipts, long grossPaise, long feePaise,
                             long taxPaise) {}

  /** Who paid on a counter line, from the masked detail the machine reported: network and last four digits, the masked UPI address, or the bank. */
  private static final String PAYER_OF_LINE = "case when pp.detail ->> 'cardLast4' is not null then coalesce(pp.detail ->> 'cardScheme', 'CARD') || ' ****' "
      + "|| (pp.detail ->> 'cardLast4') when pp.detail ->> 'upiVpa' is not null then pp.detail ->> 'upiVpa' else pp.detail ->> 'issuingBank' end";

  private static final String RECEIPT_ROWS = "with r as ("
      + "select 'ONLINE_ORDER' source, p.id, p.provider, p.money_account_id acc, null::text terminal_id, upper(coalesce(p.instrument, 'ONLINE')) method, "
      + "coalesce(p.card_network || ' ****' || p.card_last4, case when p.upi_vpa like '%@%' then left(p.upi_vpa, 2) || '***' || substring(p.upi_vpa from position('@' in p.upi_vpa)) end, p.bank) payer, "
      + "p.provider_ref reference, p.utr, p.amount_paise gross, p.fee_paise fee, p.tax_paise tax, p.net_paise net, p.status, p.settlement_ref, p.captured_at at, o.order_no doc "
      + "from payment p join orders o on o.id = p.order_id where p.captured_at >= :f and p.captured_at < :t and p.status in ('CAPTURED','REFUNDED') "
      + "union all select 'POS_SALE', pp.id, coalesce(ma.provider, 'counter'), pp.money_account_id, pp.terminal_id, pp.mode, " + PAYER_OF_LINE + ", pp.reference, "
      + "(select si.utr from settlement_item si where si.pos_payment_id = pp.id limit 1), pp.amount_paise, "
      + "(select si.fee_paise from settlement_item si where si.pos_payment_id = pp.id limit 1), "
      + "(select si.tax_paise from settlement_item si where si.pos_payment_id = pp.id limit 1), "
      + "(select si.net_paise from settlement_item si where si.pos_payment_id = pp.id limit 1), 'COMPLETED', (select s.provider_settlement_id from settlement_item si join settlement s on s.id = si.settlement_id "
      + "where si.pos_payment_id = pp.id limit 1), sale.created_at, sale.invoice_no from pos_payment pp join pos_sale sale on sale.id = pp.sale_id "
      + "left join money_account ma on ma.id = pp.money_account_id where sale.created_at >= :f and sale.created_at < :t and pp.mode <> 'CREDIT') ";
  private static final String RECEIPT_FILTER = " where (cast(:prov as text) is null or r.provider = :prov) and (cast(:acc as uuid) is null or r.acc = :acc) "
      + "and (cast(:m as text) is null or r.method = :m)";

  /** Every receipt in {@code [from, to)} from every vendor, newest first; filters are optional. */
  public Page<ReceiptLine> receiptLines(Instant from, Instant to, String provider, UUID account, String method, int page, int size) {
    List<ReceiptLine> rows = jdbc.sql(RECEIPT_ROWS + "select r.*, a.name account_name from r left join money_account a on a.id = r.acc" + RECEIPT_FILTER
        + " order by r.at desc, r.id limit :n offset :o").param("f", ts(from)).param("t", ts(to)).param("prov", provider).param("acc", account)
        .param("m", method).param("n", size + 1).param("o", (long) page * size).query((rs, i) -> new ReceiptLine(rs.getString("source"),
            rs.getObject("id", UUID.class), rs.getString("provider"), rs.getObject("acc", UUID.class), rs.getString("account_name"), rs.getString("terminal_id"),
            rs.getString("method"), rs.getString("payer"), rs.getString("reference"), rs.getString("utr"), rs.getLong("gross"), nullableLong(rs, "fee"),
            nullableLong(rs, "tax"), nullableLong(rs, "net"), rs.getString("status"), rs.getString("settlement_ref"), instant(rs, "at"), rs.getString("doc"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql(RECEIPT_ROWS + "select count(*) from (select 1 from r" + RECEIPT_FILTER + " limit "
        + (BoundedCount.CAP + 1) + ") c").param("f", ts(from)).param("t", ts(to)).param("prov", provider).param("acc", account).param("m", method)
        .query(Long.class).single());
  }

  /** Per vendor, account and method totals of the same receipts. */
  public List<ReceiptTotal> receiptTotals(Instant from, Instant to) {
    return jdbc.sql(RECEIPT_ROWS + "select r.provider, r.acc, a.name, r.method, count(*) cnt, sum(r.gross) gross, coalesce(sum(r.fee), 0) fee, "
        + "coalesce(sum(r.tax), 0) tax from r left join money_account a on a.id = r.acc group by r.provider, r.acc, a.name, r.method "
        + "order by r.provider, a.name nulls last, r.method limit 500").param("f", ts(from)).param("t", ts(to))
        .query((rs, i) -> new ReceiptTotal(rs.getString("provider"), rs.getObject("acc", UUID.class), rs.getObject("acc", UUID.class) == null ? "Unassigned"
            : rs.getString("name"), rs.getString("method"), rs.getLong("cnt"), rs.getLong("gross"), rs.getLong("fee"), rs.getLong("tax"))).list();
  }

  // ---------- settlements ----------
  private static final String SETTLEMENT_FROM = "settlement s left join money_account a on a.id = s.money_account_id where "
      + "coalesce(s.settled_at, s.created_at) >= :f and coalesce(s.settled_at, s.created_at) < :t and (cast(:acc as uuid) is null or s.money_account_id = :acc) "
      + "and (cast(:p as text) is null or s.provider = :p) and (cast(:src as text) is null or s.source = :src)";

  /** Settlements paid out in {@code [from, to)}, newest first; filters are optional. */
  public Page<SettlementRow> settlements(Instant from, Instant to, UUID account, String provider, String source, int page, int size) {
    List<SettlementRow> rows = jdbc.sql("select s.id, s.provider, s.provider_settlement_id, s.utr, s.gross_paise, s.fee_paise, s.tax_paise, s.net_paise, "
        + "s.status, s.settled_at, s.money_account_id, a.name account_name, s.source, "
        + "(select count(*) from settlement_item i where i.settlement_id = s.id) items, "
        + "(select count(*) from settlement_item i where i.settlement_id = s.id and not i.matched) unmatched "
        + "from " + SETTLEMENT_FROM + " order by coalesce(s.settled_at, s.created_at) desc, s.id limit :n offset :o")
        .param("f", ts(from)).param("t", ts(to)).param("acc", account).param("p", provider).param("src", source).param("n", size + 1)
        .param("o", (long) page * size).query((rs, i) -> new SettlementRow(rs.getObject("id", UUID.class), rs.getString("provider"),
            rs.getString("provider_settlement_id"), rs.getString("utr"), rs.getLong("gross_paise"), rs.getLong("fee_paise"), rs.getLong("tax_paise"),
            rs.getLong("net_paise"), rs.getString("status"), instant(rs, "settled_at"), rs.getObject("money_account_id", UUID.class),
            rs.getString("account_name"), rs.getString("source"), rs.getLong("items"), rs.getLong("unmatched"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from " + SETTLEMENT_FROM + " limit "
        + (BoundedCount.CAP + 1) + ") c").param("f", ts(from)).param("t", ts(to)).param("acc", account).param("p", provider).param("src", source)
        .query(Long.class).single());
  }

  /** One settlement for the detail screen. */
  public Optional<SettlementRow> settlement(UUID id) {
    return jdbc.sql("select s.id, s.provider, s.provider_settlement_id, s.utr, s.gross_paise, s.fee_paise, s.tax_paise, s.net_paise, s.status, s.settled_at, "
        + "s.money_account_id, a.name account_name, s.source, (select count(*) from settlement_item i where i.settlement_id = s.id) items, "
        + "(select count(*) from settlement_item i where i.settlement_id = s.id and not i.matched) unmatched "
        + "from settlement s left join money_account a on a.id = s.money_account_id where s.id = :id").param("id", id)
        .query((rs, i) -> new SettlementRow(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("provider_settlement_id"),
            rs.getString("utr"), rs.getLong("gross_paise"), rs.getLong("fee_paise"), rs.getLong("tax_paise"), rs.getLong("net_paise"),
            rs.getString("status"), instant(rs, "settled_at"), rs.getObject("money_account_id", UUID.class), rs.getString("account_name"),
            rs.getString("source"), rs.getLong("items"), rs.getLong("unmatched"))).optional();
  }

  /** The items of a settlement, a page at a time, with the order number or invoice number each one was matched to. */
  public Page<SettlementItemRow> settlementItems(UUID settlementId, Boolean unmatchedOnly, int page, int size) {
    String from = "settlement_item i left join payment pay on pay.id = i.payment_id left join orders o on o.id = pay.order_id "
        + "left join pos_payment pp on pp.id = i.pos_payment_id left join pos_sale sale on sale.id = pp.sale_id "
        + "where i.settlement_id = :s and (cast(:u as boolean) is not true or not i.matched)";
    List<SettlementItemRow> rows = jdbc.sql("select i.id, i.kind, i.provider_entity_id, i.gross_paise, i.fee_paise, i.tax_paise, i.net_paise, i.method, "
        + "i.settled_at, i.utr, i.reference, i.matched, i.payment_id, pay.order_id, o.order_no, i.refund_id, i.pos_payment_id, sale.invoice_no from " + from
        + " order by i.kind, i.provider_entity_id limit :n offset :o").param("s", settlementId).param("u", unmatchedOnly).param("n", size + 1)
        .param("o", (long) page * size).query((rs, i) -> new SettlementItemRow(rs.getObject("id", UUID.class), rs.getString("kind"),
            rs.getString("provider_entity_id"), rs.getLong("gross_paise"), rs.getLong("fee_paise"), rs.getLong("tax_paise"), rs.getLong("net_paise"),
            rs.getString("method"), instant(rs, "settled_at"), rs.getString("utr"), rs.getString("reference"), rs.getBoolean("matched"),
            rs.getObject("payment_id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("order_no"), rs.getObject("refund_id", UUID.class),
            rs.getObject("pos_payment_id", UUID.class), rs.getString("invoice_no"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from settlement_item i where i.settlement_id = :s "
        + "and (cast(:u as boolean) is not true or not i.matched) limit " + (BoundedCount.CAP + 1) + ") c").param("s", settlementId)
        .param("u", unmatchedOnly).query(Long.class).single());
  }

  // ---------- unsettled ----------
  private static final String UNSETTLED = "select 'ONLINE' source, p.id, o.order_no reference, p.money_account_id acc, p.amount_paise amount, p.captured_at at "
      + "from payment p join orders o on o.id = p.order_id where p.status = 'CAPTURED' and p.settlement_ref is null and p.captured_at is not null "
      + "and (cast(:before as timestamptz) is null or p.captured_at < :before) "
      + "union all select 'COUNTER', pp.id, sale.invoice_no, pp.money_account_id, pp.amount_paise, sale.created_at from pos_payment pp "
      + "join pos_sale sale on sale.id = pp.sale_id join money_account ma on ma.id = pp.money_account_id "
      + "where ((pp.mode = 'CARD' and ma.kind in ('CARD_TERMINAL','GATEWAY')) or (pp.mode = 'UPI' and ma.kind = 'CARD_TERMINAL')) and not exists (select 1 from settlement_item si where si.pos_payment_id = pp.id) "
      + "and (cast(:before as timestamptz) is null or sale.created_at < :before)";

  /** Money received but not yet reported in a settlement, oldest first; {@code receivedBefore} limits it to the overdue ones. */
  public Page<UnsettledRow> unsettled(Instant receivedBefore, int page, int size) {
    List<UnsettledRow> rows = jdbc.sql("select u.source, u.id, u.reference, u.acc, a.name, u.amount, u.at from (" + UNSETTLED + ") u "
        + "left join money_account a on a.id = u.acc order by u.at, u.id limit :n offset :o").param("before", ts(receivedBefore))
        .param("n", size + 1).param("o", (long) page * size).query((rs, i) -> new UnsettledRow(rs.getString("source"), rs.getObject("id", UUID.class),
            rs.getString("reference"), rs.getObject("acc", UUID.class), rs.getString("name"), rs.getLong("amount"), instant(rs, "at"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from (" + UNSETTLED + ") u limit "
        + (BoundedCount.CAP + 1) + ") c").param("before", ts(receivedBefore)).query(Long.class).single());
  }

  // ---------- fees ----------
  private static final String FEES = "select to_char(date_trunc('month', p.captured_at at time zone 'Asia/Kolkata'), 'YYYY-MM') as month_key, p.money_account_id acc, "
      + "a.name, count(*) cnt, sum(p.amount_paise) gross, coalesce(sum(p.fee_paise), 0) fee, coalesce(sum(p.tax_paise), 0) tax, "
      + "count(*) filter (where p.fee_paise is null) missing from payment p left join money_account a on a.id = p.money_account_id "
      + "where p.captured_at >= :f and p.captured_at < :t and p.status in ('CAPTURED','REFUNDED') group by 1, 2, 3";

  /** Gateway fee and GST on the fee per month and account for payments captured in {@code [from, to)}, newest month first. */
  public Page<FeeRow> fees(Instant from, Instant to, int page, int size) {
    List<FeeRow> rows = jdbc.sql(FEES + " order by month_key desc, a.name nulls last limit :n offset :o").param("f", ts(from)).param("t", ts(to))
        .param("n", size + 1).param("o", (long) page * size).query((rs, i) -> new FeeRow(rs.getString("month_key"), rs.getObject("acc", UUID.class),
            rs.getObject("acc", UUID.class) == null ? "Unassigned" : rs.getString("name"), rs.getLong("cnt"), rs.getLong("gross"), rs.getLong("fee"),
            rs.getLong("tax"), rs.getLong("missing"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (" + FEES + " limit " + (BoundedCount.CAP + 1) + ") g")
        .param("f", ts(from)).param("t", ts(to)).query(Long.class).single());
  }

  // ---------- book ----------
  /**
   * The movements of one account, built from {@code timeFilter} applied to each source's time column. Receipts and refunds tagged to the
   * account; for a gateway, UPI or card-terminal account the payouts leave it, and for the bank they settle into, the net arrives;
   * hand-entered entries. A later module (purchase payments) posts its outflows as entries with a {@code source_type}.
   */
  private static String bookEvents(UnaryOperator<String> timeFilter) {
    return "select p.captured_at at, 'RECEIPT' type, 'IN' dir, p.amount_paise amt, 'ONLINE_PAYMENT' src, p.id::text sid, 'Online payment' descr from payment p "
        + "where p.money_account_id = :id and p.status in ('CAPTURED','REFUNDED') and " + timeFilter.apply("p.captured_at")
        + " union all select s.created_at, 'RECEIPT', 'IN', pp.amount_paise, 'COUNTER_PAYMENT', pp.id::text, 'Counter ' || pp.mode || ' ' || s.invoice_no "
        + "from pos_payment pp join pos_sale s on s.id = pp.sale_id where pp.money_account_id = :id and " + timeFilter.apply("s.created_at")
        + " union all select r.created_at, 'REFUND', 'OUT', r.amount_paise, 'ONLINE_REFUND', r.id::text, 'Online refund' from refund r "
        + "where r.money_account_id = :id and r.status <> 'FAILED' and " + timeFilter.apply("r.created_at")
        + " union all select rt.created_at, 'REFUND', 'OUT', x.amount_paise, 'COUNTER_REFUND', x.id::text, 'Counter refund ' || rt.credit_note_no "
        + "from pos_refund_payment x join pos_return rt on rt.id = x.return_id where x.money_account_id = :id and " + timeFilter.apply("rt.created_at")
        + " union all select coalesce(s.settled_at, s.created_at), 'PAYOUT', 'OUT', s.net_paise, 'SETTLEMENT', s.id::text, 'Payout ' || coalesce(s.utr, s.provider_settlement_id) "
        + "from settlement s join money_account a on a.id = s.money_account_id where s.money_account_id = :id and a.kind in ('GATEWAY','CARD_TERMINAL','UPI') and "
        + timeFilter.apply("coalesce(s.settled_at, s.created_at)")
        + " union all select coalesce(s.settled_at, s.created_at), 'FEE', 'OUT', s.fee_paise + s.tax_paise, 'SETTLEMENT', s.id::text || ':fee', 'Fee and GST ' || coalesce(s.utr, s.provider_settlement_id) "
        + "from settlement s join money_account a on a.id = s.money_account_id where s.money_account_id = :id and a.kind in ('GATEWAY','CARD_TERMINAL','UPI') "
        + "and s.fee_paise + s.tax_paise > 0 and " + timeFilter.apply("coalesce(s.settled_at, s.created_at)")
        + " union all select coalesce(s.settled_at, s.created_at), 'PAYOUT', 'IN', s.net_paise, 'SETTLEMENT', s.id::text || ':in', 'Payout ' || coalesce(s.utr, s.provider_settlement_id) "
        + "from settlement s join money_account a on a.id = s.money_account_id where a.settles_to_account_id = :id and a.kind in ('GATEWAY','CARD_TERMINAL','UPI') and "
        + timeFilter.apply("coalesce(s.settled_at, s.created_at)")
        + " union all select e.at, 'ENTRY', e.direction, e.amount_paise, coalesce(e.source_type, 'MANUAL'), e.id::text, e.note from money_account_entry e "
        + "where e.money_account_id = :id and " + timeFilter.apply("e.at");
  }

  /** The balance of an account before {@code from}: everything that came in minus everything that went out. */
  public long openingBalance(UUID account, Instant from) {
    return jdbc.sql("select coalesce(sum(case when dir = 'IN' then amt else -amt end), 0) from (" + bookEvents(c -> c + " < :f") + ") b")
        .param("id", account).param("f", ts(from)).query(Long.class).single();
  }

  /** The book of one account in {@code [from, to)}, oldest first, each line with the running balance starting from {@code opening}. */
  public Page<BookRow> book(UUID account, Instant from, Instant to, long opening, int page, int size) {
    String events = bookEvents(c -> c + " >= :f and " + c + " < :t");
    List<BookRow> rows = jdbc.sql("select * from (select at, type, dir, amt, src, sid, descr, :opening + sum(case when dir = 'IN' then amt else -amt end) "
        + "over (order by at, type, sid rows unbounded preceding) balance from (" + events + ") b) r order by at, type, sid limit :n offset :o")
        .param("id", account).param("f", ts(from)).param("t", ts(to)).param("opening", opening).param("n", size + 1).param("o", (long) page * size)
        .query((rs, i) -> new BookRow(instant(rs, "at"), rs.getString("type"), rs.getString("dir"), rs.getLong("amt"), rs.getString("src"),
            rs.getString("sid"), rs.getString("descr"), rs.getLong("balance"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from (" + events + ") b limit "
        + (BoundedCount.CAP + 1) + ") c").param("id", account).param("f", ts(from)).param("t", ts(to)).query(Long.class).single());
  }

  // ---------- payer ----------
  /**
   * How one counter payment line was made, for the admin bill view. Everything is masked: the network and last four digits, the masked UPI address.
   * {@code utr}, fee, tax and net come from the settlement once the line is in one.
   */
  public record SalePayerRow(UUID posPaymentId, String mode, String provider, long amountPaise, String reference, String terminalId, UUID moneyAccountId,
                             String accountName, String payMethod, String cardScheme, String cardLast4, String issuingBank, String upiVpa, String authCode,
                             String settlementRef, String utr, Long feePaise, Long taxPaise, Long netPaise, Instant settledAt) {}

  /** How the payment lines of a counter bill were made, in the order they were taken. */
  public List<SalePayerRow> payersOfSale(UUID saleId) {
    return jdbc.sql("select pp.id, pp.mode, coalesce(ma.provider, 'counter') provider, pp.amount_paise, pp.reference, pp.terminal_id, pp.money_account_id, ma.name, "
        + "pp.detail ->> 'payMethod' pay_method, pp.detail ->> 'cardScheme' scheme, pp.detail ->> 'cardLast4' last4, pp.detail ->> 'issuingBank' bank, "
        + "pp.detail ->> 'upiVpa' vpa, pp.detail ->> 'authCode' auth, s.provider_settlement_id, si.utr, si.fee_paise, si.tax_paise, si.net_paise, "
        + "coalesce(si.settled_at, s.settled_at) settled_at from pos_payment pp left join money_account ma on ma.id = pp.money_account_id "
        + "left join settlement_item si on si.pos_payment_id = pp.id left join settlement s on s.id = si.settlement_id where pp.sale_id = :s "
        + "order by pp.id limit 20").param("s", saleId).query((rs, i) -> new SalePayerRow(rs.getObject("id", UUID.class), rs.getString("mode"),
            rs.getString("provider"), rs.getLong("amount_paise"), rs.getString("reference"), rs.getString("terminal_id"), rs.getObject("money_account_id", UUID.class),
            rs.getString("name"), rs.getString("pay_method"), rs.getString("scheme"), rs.getString("last4"), rs.getString("bank"), rs.getString("vpa"),
            rs.getString("auth"), rs.getString("provider_settlement_id"), rs.getString("utr"), nullableLong(rs, "fee_paise"), nullableLong(rs, "tax_paise"),
            nullableLong(rs, "net_paise"), instant(rs, "settled_at"))).list();
  }

  /** How the online payments of an order were made, newest first. */
  public List<PayerRow> payersOfOrder(UUID orderId) {
    return jdbc.sql("select p.id, p.provider, p.status, p.amount_paise, p.instrument, p.upi_vpa, p.card_network, p.card_last4, p.bank, p.fee_paise, p.tax_paise, "
        + "p.net_paise, p.settlement_ref, p.utr, p.settled_at, p.money_account_id, a.name, p.provider_ref, p.captured_at from payment p "
        + "left join money_account a on a.id = p.money_account_id where p.order_id = :o order by p.created_at desc limit 20").param("o", orderId)
        .query((rs, i) -> new PayerRow(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("status"), rs.getLong("amount_paise"),
            rs.getString("instrument"), rs.getString("upi_vpa"), rs.getString("card_network"), rs.getString("card_last4"), rs.getString("bank"),
            nullableLong(rs, "fee_paise"), nullableLong(rs, "tax_paise"), nullableLong(rs, "net_paise"), rs.getString("settlement_ref"), rs.getString("utr"),
            instant(rs, "settled_at"), rs.getObject("money_account_id", UUID.class), rs.getString("name"), rs.getString("provider_ref"),
            instant(rs, "captured_at"))).list();
  }
}
