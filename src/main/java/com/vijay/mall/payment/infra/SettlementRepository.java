package com.vijay.mall.payment.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import com.vijay.mall.payment.domain.SettlementProvider;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores settlements and their items, the exception queue and the sync lock ({@code settlement*} tables), and links them to payments. */
@Repository
public class SettlementRepository {
  /** A settlement as stored. */
  public record Settlement(UUID id, String provider, String providerSettlementId, String utr, long grossPaise, long feePaise, long taxPaise,
                           long netPaise, String status, Instant settledAt, UUID moneyAccountId, String source) {}

  /** One line of a settlement as stored. */
  public record Item(UUID id, UUID settlementId, String kind, String providerEntityId, UUID paymentId, UUID refundId, UUID posPaymentId,
                     long grossPaise, long feePaise, long taxPaise, long netPaise, String method, Instant settledAt, String utr,
                     String reference, boolean matched, String detail) {}

  /** A payment, refund or counter line a settlement item can be matched to. */
  public record Match(UUID id, long amountPaise) {}

  /** A problem for a person to look at. */
  public record ExceptionRow(UUID id, String kind, UUID settlementId, UUID settlementItemId, UUID paymentId, String reference,
                             Long expectedPaise, Long actualPaise, String status, String note, Instant createdAt, Instant resolvedAt) {}

  /** A captured online payment with no settlement yet. */
  public record Unsettled(UUID paymentId, UUID orderId, String provider, long amountPaise, Instant capturedAt) {}

  /** A payment whose method detail has not been fetched. */
  public record NeedsDetail(UUID paymentId, String provider, String providerRef) {}

  private final JdbcClient jdbc;

  SettlementRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Instant instant(ResultSet rs, String c) throws SQLException {
    Timestamp t = rs.getTimestamp(c);
    return t == null ? null : t.toInstant();
  }

  private static Timestamp ts(Instant t) { return t == null ? null : Timestamp.from(t); }

  private static Long nullableLong(ResultSet rs, String c) throws SQLException {
    long v = rs.getLong(c);
    return rs.wasNull() ? null : v;
  }

  private Settlement settlement(ResultSet rs, int i) throws SQLException {
    return new Settlement(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("provider_settlement_id"), rs.getString("utr"),
        rs.getLong("gross_paise"), rs.getLong("fee_paise"), rs.getLong("tax_paise"), rs.getLong("net_paise"), rs.getString("status"),
        instant(rs, "settled_at"), rs.getObject("money_account_id", UUID.class), rs.getString("source"));
  }

  private static final String SETTLEMENT_COLUMNS = "id, provider, provider_settlement_id, utr, gross_paise, fee_paise, tax_paise, net_paise, status, "
      + "settled_at, money_account_id, source";

  /** The settlement a provider calls this, if we have it. */
  public Optional<Settlement> find(String provider, String providerSettlementId) {
    return jdbc.sql("select " + SETTLEMENT_COLUMNS + " from settlement where provider = :p and provider_settlement_id = :s")
        .param("p", provider).param("s", providerSettlementId).query(this::settlement).optional();
  }

  /** One settlement by our id. */
  public Optional<Settlement> find(UUID id) {
    return jdbc.sql("select " + SETTLEMENT_COLUMNS + " from settlement where id = :id").param("id", id).query(this::settlement).optional();
  }

  /** Saves a settlement, or refreshes its amounts and status when the provider's id is already known; returns our id either way. */
  @Transactional
  public UUID upsert(Settlement s, Instant now) {
    return jdbc.sql("insert into settlement (id, provider, provider_settlement_id, utr, gross_paise, fee_paise, tax_paise, net_paise, status, settled_at, "
        + "money_account_id, source, created_at, updated_at) values (:id, :p, :sid, :utr, :g, :f, :t, :n, :st, :at, :acc, :src, :now, :now) "
        + "on conflict (provider, provider_settlement_id) do update set utr = coalesce(excluded.utr, settlement.utr), gross_paise = excluded.gross_paise, "
        + "fee_paise = excluded.fee_paise, tax_paise = excluded.tax_paise, net_paise = excluded.net_paise, status = excluded.status, "
        + "settled_at = coalesce(excluded.settled_at, settlement.settled_at), "
        + "money_account_id = coalesce(settlement.money_account_id, excluded.money_account_id), updated_at = excluded.updated_at returning id")
        .param("id", s.id()).param("p", s.provider()).param("sid", s.providerSettlementId()).param("utr", s.utr()).param("g", s.grossPaise())
        .param("f", s.feePaise()).param("t", s.taxPaise()).param("n", s.netPaise()).param("st", s.status()).param("at", ts(s.settledAt()))
        .param("acc", s.moneyAccountId()).param("src", s.source()).param("now", ts(now)).query(UUID.class).single();
  }

  /** How many items a settlement has stored. */
  public long itemCount(UUID settlementId) {
    return jdbc.sql("select count(*) from settlement_item where settlement_id = :id").param("id", settlementId).query(Long.class).single();
  }

  /** Stores an item; returns its id, or empty when the settlement already has that line (a repeated sync). */
  @Transactional
  public Optional<UUID> insertItem(Item i) {
    return jdbc.sql("insert into settlement_item (id, settlement_id, kind, provider_entity_id, payment_id, refund_id, pos_payment_id, gross_paise, "
        + "fee_paise, tax_paise, net_paise, method, settled_at, utr, reference, matched, detail) values (:id, :s, :k, :e, :pay, :ref, :pos, :g, :f, :t, :n, "
        + ":m, :at, :utr, :rf, :matched, cast(:detail as jsonb)) on conflict (settlement_id, kind, provider_entity_id) do nothing returning id")
        .param("id", i.id()).param("s", i.settlementId()).param("k", i.kind()).param("e", i.providerEntityId()).param("pay", i.paymentId())
        .param("ref", i.refundId()).param("pos", i.posPaymentId()).param("g", i.grossPaise()).param("f", i.feePaise()).param("t", i.taxPaise())
        .param("n", i.netPaise()).param("m", i.method()).param("at", ts(i.settledAt())).param("utr", i.utr()).param("rf", i.reference())
        .param("matched", i.matched()).param("detail", i.detail()).query(UUID.class).optional();
  }

  /** The items of a settlement, in the order they were stored. */
  public List<Item> items(UUID settlementId) {
    return jdbc.sql("select id, settlement_id, kind, provider_entity_id, payment_id, refund_id, pos_payment_id, gross_paise, fee_paise, tax_paise, "
        + "net_paise, method, settled_at, utr, reference, matched, detail::text detail from settlement_item where settlement_id = :id order by kind, provider_entity_id limit 5001")
        .param("id", settlementId).query((rs, n) -> new Item(rs.getObject("id", UUID.class), rs.getObject("settlement_id", UUID.class),
            rs.getString("kind"), rs.getString("provider_entity_id"), rs.getObject("payment_id", UUID.class), rs.getObject("refund_id", UUID.class),
            rs.getObject("pos_payment_id", UUID.class), rs.getLong("gross_paise"), rs.getLong("fee_paise"), rs.getLong("tax_paise"),
            rs.getLong("net_paise"), rs.getString("method"), instant(rs, "settled_at"), rs.getString("utr"), rs.getString("reference"),
            rs.getBoolean("matched"), rs.getString("detail"))).list();
  }

  // ---------- matching ----------
  /** The online payment with this provider payment id. */
  public Optional<Match> paymentByProviderRef(String ref) {
    return jdbc.sql("select id, amount_paise from payment where provider_ref = :r order by created_at limit 1").param("r", ref)
        .query((rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getLong("amount_paise"))).optional();
  }

  /** The refund with this provider refund id. */
  public Optional<Match> refundByProviderRef(String ref) {
    return jdbc.sql("select id, amount_paise from refund where provider_ref = :r order by created_at limit 1").param("r", ref)
        .query((rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getLong("amount_paise"))).optional();
  }

  /** A counter UPI or card line with this reference that no settlement item has claimed yet; the line with the same amount is preferred. */
  public Optional<Match> freePosPaymentByReference(String ref, long amountPaise) {
    return jdbc.sql("select p.id, p.amount_paise from pos_payment p where p.reference = :r and p.mode in ('UPI','CARD') "
        + "and not exists (select 1 from settlement_item i where i.pos_payment_id = p.id) order by (p.amount_paise = :a) desc, p.id limit 1")
        .param("r", ref).param("a", amountPaise).query((rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getLong("amount_paise"))).optional();
  }

  /**
   * A counter line of a Paytm machine payment whose RRN or order id (the payment id without dashes) is one of {@code refs} and that no settlement
   * item has claimed yet; the line with the same amount is preferred.
   */
  public Optional<Match> freePosPaymentByMachineRef(List<String> refs, long amountPaise) {
    return jdbc.sql("select p.id, p.amount_paise from pos_payment p join terminal_payment t on t.id = p.terminal_payment_id "
        + "where (t.rrn in (:r) or t.merchant_transaction_id in (:r)) and not exists (select 1 from settlement_item i where i.pos_payment_id = p.id) "
        + "order by (p.amount_paise = :a) desc, p.id limit 1").param("r", refs).param("a", amountPaise)
        .query((rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getLong("amount_paise"))).optional();
  }

  /**
   * Counter UPI lines of the amount, taken on {@code day} (India time) on a UPI account of this vendor, with no machine payment and no settlement item
   * yet: a customer who paid the vendor's QR sticker has no payment of ours, so amount, day and vendor are all there is to match on. At most two are
   * returned, which is enough to tell whether the answer is unique.
   */
  public List<Match> freeStickerUpiPayments(String provider, long amountPaise, LocalDate day) {
    return jdbc.sql("select p.id, p.amount_paise from pos_payment p join pos_sale s on s.id = p.sale_id join money_account a on a.id = p.money_account_id "
        + "where p.mode = 'UPI' and p.terminal_payment_id is null and p.amount_paise = :a and a.kind = 'UPI' and a.provider = :p "
        + "and (s.created_at at time zone 'Asia/Kolkata')::date = :d and not exists (select 1 from settlement_item i where i.pos_payment_id = p.id) "
        + "order by p.id limit 2").param("a", amountPaise).param("p", provider).param("d", Date.valueOf(day))
        .query((rs, i) -> new Match(rs.getObject("id", UUID.class), rs.getLong("amount_paise"))).list();
  }

  /** Records on the payment where its money went and what it cost. */
  @Transactional
  public void markPaymentSettled(UUID paymentId, String settlementRef, String utr, Instant settledAt, long feePaise, long taxPaise, long netPaise,
                                 String method, Instant now) {
    jdbc.sql("update payment set settlement_ref = :s, utr = coalesce(:utr, utr), settled_at = :at, fee_paise = :f, tax_paise = :t, net_paise = :n, "
        + "instrument = coalesce(instrument, :m), updated_at = :now where id = :id")
        .param("s", settlementRef).param("utr", utr).param("at", ts(settledAt)).param("f", feePaise).param("t", taxPaise).param("n", netPaise)
        .param("m", method).param("now", ts(now)).param("id", paymentId).update();
  }

  // ---------- exceptions ----------
  /** Raises a problem; false when the same one is already on the list (open or resolved). */
  @Transactional
  public boolean raise(String kind, String dedupeKey, UUID settlementId, UUID itemId, UUID paymentId, String reference, Long expected, Long actual,
                       Instant now) {
    return jdbc.sql("insert into settlement_exception (id, kind, dedupe_key, settlement_id, settlement_item_id, payment_id, reference, expected_paise, "
        + "actual_paise, status, created_at) values (:id, :k, :d, :s, :i, :p, :r, :e, :a, 'OPEN', :now) on conflict (dedupe_key) do nothing")
        .param("id", UUID.randomUUID()).param("k", kind).param("d", dedupeKey).param("s", settlementId).param("i", itemId).param("p", paymentId)
        .param("r", reference).param("e", expected).param("a", actual).param("now", ts(now)).update() == 1;
  }

  /** Closes the "overdue" problem of a payment that has now settled. */
  @Transactional
  public void closeOverdue(UUID paymentId, Instant now) {
    jdbc.sql("update settlement_exception set status = 'RESOLVED', note = 'Settled', resolved_at = :now where dedupe_key = :k and status = 'OPEN'")
        .param("now", ts(now)).param("k", "overdue:" + paymentId).update();
  }

  /** Closes an open problem with a note; false when it was not open. */
  @Transactional
  public boolean resolve(UUID id, String note, UUID actor, Instant now) {
    return jdbc.sql("update settlement_exception set status = 'RESOLVED', note = :n, resolved_by = :a, resolved_at = :now where id = :id and status = 'OPEN'")
        .param("n", note).param("a", actor).param("now", ts(now)).param("id", id).update() == 1;
  }

  /** One problem. */
  public Optional<ExceptionRow> findException(UUID id) {
    return jdbc.sql(EXCEPTION_SELECT + " where id = :id").param("id", id).query(this::exception).optional();
  }

  private static final String EXCEPTION_SELECT = "select id, kind, settlement_id, settlement_item_id, payment_id, reference, expected_paise, "
      + "actual_paise, status, note, created_at, resolved_at from settlement_exception";

  private ExceptionRow exception(ResultSet rs, int i) throws SQLException {
    return new ExceptionRow(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("settlement_id", UUID.class),
        rs.getObject("settlement_item_id", UUID.class), rs.getObject("payment_id", UUID.class), rs.getString("reference"),
        nullableLong(rs, "expected_paise"), nullableLong(rs, "actual_paise"), rs.getString("status"), rs.getString("note"),
        instant(rs, "created_at"), instant(rs, "resolved_at"));
  }

  /** Problems, newest first; {@code status} and {@code kind} are optional filters. */
  public Page<ExceptionRow> exceptions(String status, String kind, int page, int size) {
    String where = " where (cast(:s as text) is null or status = :s) and (cast(:k as text) is null or kind = :k)";
    List<ExceptionRow> rows = jdbc.sql(EXCEPTION_SELECT + where + " order by created_at desc, id limit :n offset :o").param("s", status).param("k", kind)
        .param("n", size + 1).param("o", (long) page * size).query(this::exception).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from settlement_exception" + where + " limit "
        + (BoundedCount.CAP + 1) + ") c").param("s", status).param("k", kind).query(Long.class).single());
  }

  /** Captured online payments captured before {@code capturedBefore} with no settlement, oldest first. */
  public List<Unsettled> overdue(Instant capturedBefore, int limit) {
    return jdbc.sql("select id, order_id, provider, amount_paise, captured_at from payment where status = 'CAPTURED' and settlement_ref is null "
        + "and captured_at < :c order by captured_at, id limit :n").param("c", ts(capturedBefore)).param("n", limit)
        .query((rs, i) -> new Unsettled(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("provider"),
            rs.getLong("amount_paise"), instant(rs, "captured_at"))).list();
  }

  // ---------- payment detail ----------
  /** Captured or refunded payments whose method detail is still missing, oldest first. */
  public List<NeedsDetail> needingDetail(int limit) {
    return jdbc.sql("select id, provider, provider_ref from payment where status in ('CAPTURED','REFUNDED') and detail_fetched_at is null "
        + "and provider_ref is not null and captured_at is not null order by captured_at limit :n").param("n", limit)
        .query((rs, i) -> new NeedsDetail(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("provider_ref"))).list();
  }

  /** Stores what the provider said about how a payment was made; a fee already written by a settlement is kept. Never stores a card number. */
  @Transactional
  public void savePaymentDetail(UUID paymentId, SettlementProvider.PaymentDetail d, Instant now) {
    jdbc.sql("update payment set instrument = coalesce(:i, instrument), upi_vpa = :v, card_network = :cn, card_last4 = :l4, bank = :b, "
        + "fee_paise = coalesce(fee_paise, :f), tax_paise = coalesce(tax_paise, :t), detail_fetched_at = :now where id = :id")
        .param("i", d.instrument()).param("v", d.upiVpa()).param("cn", d.cardNetwork()).param("l4", d.cardLast4()).param("b", d.bank())
        .param("f", d.feePaise()).param("t", d.taxPaise()).param("now", ts(now)).param("id", paymentId).update();
  }

  /** Marks the detail as looked up although the provider had none, so the job does not ask again and again. */
  @Transactional
  public void markDetailFetched(UUID paymentId, Instant now) {
    jdbc.sql("update payment set detail_fetched_at = :now where id = :id").param("now", ts(now)).param("id", paymentId).update();
  }

  // ---------- sync lock ----------
  /** Claims the right to sync this provider until {@code until}; false when another instance holds an unexpired claim. */
  @Transactional
  public boolean claimSync(String provider, Instant now, Instant until) {
    return jdbc.sql("insert into settlement_sync_state (provider, locked_until) values (:p, :u) on conflict (provider) do update "
        + "set locked_until = excluded.locked_until where settlement_sync_state.locked_until < :now")
        .param("p", provider).param("u", ts(until)).param("now", ts(now)).update() == 1;
  }

  /** Releases the claim and remembers how the run went ({@code error} null = fine). */
  @Transactional
  public void finishSync(String provider, Instant now, String error) {
    jdbc.sql("update settlement_sync_state set locked_until = :now, last_synced_at = case when cast(:e as text) is null then :now else last_synced_at end, last_error = cast(:e as text) "
        + "where provider = :p").param("now", ts(now)).param("e", error).param("p", provider).update();
  }
}
