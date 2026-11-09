package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The money side of a supplier: the statement (every purchase, payment, debit note, credit use and refund in date order with a running
 * balance), the credit the supplier owes the shop, and the refunds. A balance is what the shop owes the supplier; negative means the
 * supplier owes the shop. The balance is always the sum of {@code purchase.due_paise}.
 */
@Repository
public class SupplierLedgerRepository {
  /** One line of the statement; {@code amountPaise} is signed (plus: the shop owes more), {@code balancePaise} the running balance after it. */
  public record Row(LocalDate date, Instant at, String kind, UUID refId, UUID purchaseId, String invoiceNo, String mode, String reference,
                    long amountPaise, long balancePaise) {}

  /** The statement's opening balance (before the first day) and the sum of the lines in range. */
  public record Totals(long openingPaise, long inRangePaise) {}

  /** A debit note with how much of it was already used as credit. */
  public record Note(UUID id, UUID purchaseId, UUID supplierId, String noteNo, long totalPaise, long creditUsedPaise) {}

  /** A refund from a supplier. */
  public record Refund(UUID id, UUID supplierId, long amountPaise, String mode, String reference, UUID moneyAccountId, Instant receivedAt,
                       UUID createdBy, Instant createdAt) {}

  /** A purchase's due figures, read while the purchase is locked. */
  public record Due(UUID id, UUID supplierId, long duePaise) {}

  /** The zone the shop's days are counted in. */
  private static final String ZONE = "Asia/Kolkata";

  /**
   * Every event that moves a supplier's balance, one select per kind. Purchases are dated by invoice date, everything else by the India
   * date of its timestamp. {@code rank} orders a purchase before the payments of the same day. Credit used is the plus side of a payment
   * made with a debit note (the payment itself is the minus side on the purchase it pays).
   */
  private static final String EVENTS = """
      select p.invoice_date as d, p.created_at as ts, 'PURCHASE' as kind, p.id as ref_id, p.id as purchase_id, p.invoice_no as invoice_no,
        '' as mode, p.invoice_no as reference, p.total_cost_paise as delta, 1 as rank
      from purchase p where p.supplier_id = :s
      union all
      select (pp.paid_at at time zone '%1$s')::date, pp.paid_at, 'PAYMENT', pp.id, pp.purchase_id, p.invoice_no, pp.mode, pp.reference, -pp.amount_paise, 2
      from purchase_payment pp join purchase p on p.id = pp.purchase_id where p.supplier_id = :s
      union all
      select (pp.paid_at at time zone '%1$s')::date, pp.paid_at, 'CREDIT_USED', pp.id, pp.purchase_id, p.invoice_no, pp.mode, pp.reference, pp.amount_paise, 3
      from purchase_payment pp join purchase p on p.id = pp.purchase_id where p.supplier_id = :s and pp.mode = 'CREDIT_NOTE'
      union all
      select (r.created_at at time zone '%1$s')::date, r.created_at, 'DEBIT_NOTE', r.id, r.purchase_id, p.invoice_no, '', r.note_no, -r.total_paise, 4
      from purchase_return r join purchase p on p.id = r.purchase_id where p.supplier_id = :s
      union all
      select (f.received_at at time zone '%1$s')::date, f.received_at, 'SUPPLIER_REFUND', f.id, null::uuid, '', f.mode, f.reference, f.amount_paise, 5
      from supplier_refund f where f.supplier_id = :s""".formatted(ZONE);

  private final JdbcClient jdbc;

  SupplierLedgerRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Refund refund(ResultSet r, int row) throws SQLException {
    return new Refund(r.getObject("id", UUID.class), r.getObject("supplier_id", UUID.class), r.getLong("amount_paise"), r.getString("mode"),
        r.getString("reference"), r.getObject("money_account_id", UUID.class), Sql.instant(r, "received_at"), r.getObject("created_by", UUID.class),
        Sql.instant(r, "created_at"));
  }

  /** Locks the supplier row until the transaction ends, so credit uses and refunds of one supplier run one after the other; false when unknown. */
  public boolean lockSupplier(UUID supplierId) {
    return jdbc.sql("select id from supplier where id = :id for update").param("id", supplierId).query(UUID.class).optional().isPresent();
  }

  /** The supplier of a purchase (no lock). */
  public Optional<UUID> supplierOf(UUID purchaseId) {
    return jdbc.sql("select supplier_id from purchase where id = :id").param("id", purchaseId).query(UUID.class).optional();
  }

  /** Locks the purchases (in id order, so two requests cannot wait for each other) and reads their due amounts. */
  public Map<UUID, Due> lockPurchases(List<UUID> ids) {
    Map<UUID, Due> out = new LinkedHashMap<>();
    jdbc.sql("select id, supplier_id, due_paise from purchase where id in (:ids) order by id for update").param("ids", ids)
        .query((r, i) -> new Due(r.getObject("id", UUID.class), r.getObject("supplier_id", UUID.class), r.getLong("due_paise"))).list()
        .forEach(d -> out.put(d.id(), d));
    return out;
  }

  /** A debit note, locked until the transaction ends. */
  public Optional<Note> lockNote(UUID id) {
    return jdbc.sql("""
        select r.id, r.purchase_id, p.supplier_id, r.note_no, r.total_paise, r.credit_used_paise
        from purchase_return r join purchase p on p.id = r.purchase_id where r.id = :id for update of r""").param("id", id)
        .query((r, i) -> new Note(r.getObject("id", UUID.class), r.getObject("purchase_id", UUID.class), r.getObject("supplier_id", UUID.class),
            r.getString("note_no"), r.getLong("total_paise"), r.getLong("credit_used_paise"))).optional();
  }

  /**
   * Takes {@code amount} out of a purchase's credit, only while the purchase still has that much credit (a conditional update, so a race cannot
   * use credit twice); false when it does not.
   */
  public boolean useCredit(UUID purchaseId, long amount) {
    return jdbc.sql("""
        update purchase set credit_used_paise = credit_used_paise + :a
        where id = :id and total_cost_paise - returned_paise - paid_paise + credit_used_paise + :a <= 0""")
        .param("a", amount).param("id", purchaseId).update() == 1;
  }

  /** Adds to what a debit note has given as credit. */
  public void addNoteCreditUsed(UUID noteId, long amount) {
    jdbc.sql("update purchase_return set credit_used_paise = credit_used_paise + :a where id = :id").param("a", amount).param("id", noteId).update();
  }

  /** Links a payment made with credit to the debit note that funded it. */
  public void linkNote(UUID paymentId, UUID noteId) {
    jdbc.sql("update purchase_payment set debit_note_id = :n where id = :id").param("n", noteId).param("id", paymentId).update();
  }

  /** The purchases of a supplier that have credit, oldest invoice first, locked until the transaction ends. */
  public List<Due> lockCreditPurchases(UUID supplierId) {
    return jdbc.sql("select id, supplier_id, due_paise from purchase where supplier_id = :s and due_paise < 0 order by invoice_date, id for update")
        .param("s", supplierId)
        .query((r, i) -> new Due(r.getObject("id", UUID.class), r.getObject("supplier_id", UUID.class), r.getLong("due_paise"))).list();
  }

  /** The credit of a supplier: the sum of what its purchases have overpaid or returned beyond what was owed. */
  public long credit(UUID supplierId) {
    return jdbc.sql("select coalesce(sum(-due_paise), 0) from purchase where supplier_id = :s and due_paise < 0").param("s", supplierId)
        .query(Long.class).single();
  }

  /** What the shop owes the supplier now: the sum of the due amounts of its purchases (negative: the supplier owes the shop). */
  public long balance(UUID supplierId) {
    return jdbc.sql("select coalesce(sum(due_paise), 0) from purchase where supplier_id = :s").param("s", supplierId).query(Long.class).single();
  }

  /** Stores a refund. */
  public void insertRefund(Refund f) {
    jdbc.sql("""
        insert into supplier_refund (id, supplier_id, amount_paise, mode, reference, money_account_id, received_at, created_by, created_at)
        values (:id, :supplier, :amount, :mode, :ref, :account, :receivedAt, :by, :at)""")
        .param("id", f.id()).param("supplier", f.supplierId()).param("amount", f.amountPaise()).param("mode", f.mode()).param("ref", f.reference())
        .param("account", f.moneyAccountId(), java.sql.Types.OTHER).param("receivedAt", Sql.ts(f.receivedAt())).param("by", f.createdBy())
        .param("at", Sql.ts(f.createdAt())).update();
  }

  /** Finds a refund by id. */
  public Optional<Refund> findRefund(UUID id) {
    return jdbc.sql("select * from supplier_refund where id = :id").param("id", id).query(SupplierLedgerRepository::refund).optional();
  }

  /** The refunds of a supplier, newest first (at most {@code limit}). */
  public List<Refund> refunds(UUID supplierId, int limit) {
    return jdbc.sql("select * from supplier_refund where supplier_id = :s order by received_at desc, id desc limit :n").param("s", supplierId)
        .param("n", limit).query(SupplierLedgerRepository::refund).list();
  }

  private static String range(LocalDate from, LocalDate to, Map<String, Object> params) {
    List<String> c = new ArrayList<>();
    if (from != null) {
      c.add("e.d >= :from");
      params.put("from", from);
    }
    if (to != null) {
      c.add("e.d <= :to");
      params.put("to", to);
    }
    return c.isEmpty() ? "" : " where " + String.join(" and ", c);
  }

  /**
   * The balance before {@code from} (zero without {@code from}) and the sum of the lines from {@code from} to {@code to}: one pass over the
   * supplier's events. The closing balance is the sum of both.
   */
  public Totals totals(UUID supplierId, LocalDate from, LocalDate to) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = range(from, to, params);
    String openingFilter = from == null ? "false" : "e.d < :from";
    var q = jdbc.sql("select coalesce(sum(e.delta) filter (where " + openingFilter + "), 0) as opening, coalesce(sum(e.delta) filter (where "
        + (where.isEmpty() ? "true" : where.substring(" where ".length())) + "), 0) as in_range from (" + EVENTS + ") e").param("s", supplierId);
    if (from != null) q.param("from", from);
    if (to != null) q.param("to", to);
    return q.query((r, i) -> new Totals(r.getLong("opening"), r.getLong("in_range"))).single();
  }

  /**
   * One page of the statement in date order. The running balance starts at {@code opening} and is a window sum over the lines in range, so it
   * is exact on every page and needs no second pass.
   */
  public Page<Row> statement(UUID supplierId, LocalDate from, LocalDate to, long opening, int page, int size) {
    Map<String, Object> params = new LinkedHashMap<>();
    String where = range(from, to, params);
    var q = jdbc.sql("""
        select * from (select e.d, e.ts, e.kind, e.ref_id, e.purchase_id, e.invoice_no, e.mode, e.reference, e.delta, e.rank,
          :opening + sum(e.delta) over (order by e.d, e.rank, e.ts, e.ref_id) as balance
        from (%s) e%s) w order by w.d, w.rank, w.ts, w.ref_id limit %d offset %d""".formatted(EVENTS, where, size + 1, (long) page * size))
        .param("s", supplierId).param("opening", opening);
    params.forEach(q::param);
    List<Row> fetched = q.query((r, i) -> new Row(r.getObject("d", LocalDate.class), Sql.instant(r, "ts"), r.getString("kind"),
        r.getObject("ref_id", UUID.class), r.getObject("purchase_id", UUID.class), r.getString("invoice_no"), r.getString("mode"),
        r.getString("reference"), r.getLong("delta"), r.getLong("balance"))).list();
    return BoundedCount.assemble(fetched, page, size, () -> {
      var count = jdbc.sql("select count(*) from (select 1 from (" + EVENTS + ") e" + where + " limit " + (BoundedCount.CAP + 1) + ") bounded")
          .param("s", supplierId);
      params.forEach(count::param);
      return count.query(Long.class).single();
    });
  }
}
