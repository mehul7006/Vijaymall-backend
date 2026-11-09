package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stores purchase returns (debit notes): the document in {@code purchase_return} and the returned lines in {@code purchase_return_item}. */
@Repository
public class PurchaseReturnRepository {
  /** The money of a return, or of what was already returned on a line: taxable value, the three taxes, cess and the total, in paise. */
  public record Amounts(long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise, long totalPaise) {
    /** Nothing returned. */
    public static final Amounts ZERO = new Amounts(0, 0, 0, 0, 0, 0);

    /** The sum of two amounts. */
    public Amounts plus(Amounts o) {
      return new Amounts(taxablePaise + o.taxablePaise, cgstPaise + o.cgstPaise, sgstPaise + o.sgstPaise, igstPaise + o.igstPaise,
          cessPaise + o.cessPaise, totalPaise + o.totalPaise);
    }
  }

  /** A debit note with the delivery it belongs to. */
  public record Return(UUID id, UUID purchaseId, UUID supplierId, String supplierName, String invoiceNo, String noteNo, String reason, Amounts amounts,
                       UUID createdBy, Instant createdAt) {}

  /** One returned line. */
  public record Item(UUID id, UUID returnId, UUID purchaseItemId, UUID productId, int qty, Amounts amounts) {}

  /** The filters of the debit note list; null means "any". */
  public record Filter(UUID purchaseId, UUID supplierId, Instant from, Instant toExclusive) {}

  private static final String SELECT = """
      select r.*, p.supplier_id, p.supplier_name, p.invoice_no from purchase_return r join purchase p on p.id = r.purchase_id""";

  private final JdbcClient jdbc;

  PurchaseReturnRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Return ret(ResultSet r, int row) throws SQLException {
    return new Return(r.getObject("id", UUID.class), r.getObject("purchase_id", UUID.class), r.getObject("supplier_id", UUID.class),
        r.getString("supplier_name"), r.getString("invoice_no"), r.getString("note_no"), r.getString("reason"), amounts(r),
        r.getObject("created_by", UUID.class), Sql.instant(r, "created_at"));
  }

  private static Amounts amounts(ResultSet r) throws SQLException {
    return new Amounts(r.getLong("taxable_paise"), r.getLong("cgst_paise"), r.getLong("sgst_paise"), r.getLong("igst_paise"), r.getLong("cess_paise"),
        r.getLong("total_paise"));
  }

  private static Item item(ResultSet r, int row) throws SQLException {
    return new Item(r.getObject("id", UUID.class), r.getObject("return_id", UUID.class), r.getObject("purchase_item_id", UUID.class),
        r.getObject("product_id", UUID.class), r.getInt("qty"), amounts(r));
  }

  /** The next debit note number, for example {@code DN-000012}. Numbers are never reused, but a failed return leaves a gap. */
  public String nextNoteNo() {
    long n = jdbc.sql("select nextval('purchase_return_no_seq')").query(Long.class).single();
    return String.format("DN-%06d", n);
  }

  /** Finds a debit note by id. */
  public Optional<Return> find(UUID id) { return jdbc.sql(SELECT + " where r.id = :id").param("id", id).query(PurchaseReturnRepository::ret).optional(); }

  /** The lines of many debit notes, keyed by debit note. */
  public Map<UUID, List<Item>> itemsOf(List<UUID> returnIds) {
    Map<UUID, List<Item>> out = new HashMap<>();
    if (returnIds.isEmpty()) return out;
    jdbc.sql("select * from purchase_return_item where return_id in (:ids) order by id").param("ids", returnIds).query(PurchaseReturnRepository::item).list()
        .forEach(i -> out.computeIfAbsent(i.returnId(), k -> new ArrayList<>()).add(i));
    return out;
  }

  /** What was already returned on each of these purchase lines. */
  public Map<UUID, Amounts> returnedSoFar(List<UUID> purchaseItemIds) {
    Map<UUID, Amounts> out = new HashMap<>();
    jdbc.sql("""
        select purchase_item_id, sum(taxable_paise) as taxable_paise, sum(cgst_paise) as cgst_paise, sum(sgst_paise) as sgst_paise,
          sum(igst_paise) as igst_paise, sum(cess_paise) as cess_paise, sum(total_paise) as total_paise
        from purchase_return_item where purchase_item_id in (:ids) group by purchase_item_id""").param("ids", purchaseItemIds)
        .query((r, i) -> Map.entry(r.getObject("purchase_item_id", UUID.class), amounts(r))).list().forEach(e -> out.put(e.getKey(), e.getValue()));
    return out;
  }

  /** Stores the debit note and its lines. A repeated idempotency key fails with a duplicate-key error. */
  public void insert(Return r, String idemKey, List<Item> items) {
    Amounts a = r.amounts();
    jdbc.sql("""
        insert into purchase_return (id, purchase_id, note_no, reason, taxable_paise, cgst_paise, sgst_paise, igst_paise, cess_paise, total_paise,
          idem_key, created_by, created_at)
        values (:id, :purchase, :no, :reason, :taxable, :cgst, :sgst, :igst, :cess, :total, :idem, :by, :at)""")
        .param("id", r.id()).param("purchase", r.purchaseId()).param("no", r.noteNo()).param("reason", r.reason()).param("taxable", a.taxablePaise())
        .param("cgst", a.cgstPaise()).param("sgst", a.sgstPaise()).param("igst", a.igstPaise()).param("cess", a.cessPaise())
        .param("total", a.totalPaise()).param("idem", idemKey).param("by", r.createdBy()).param("at", Sql.ts(r.createdAt())).update();
    for (Item i : items) {
      Amounts x = i.amounts();
      jdbc.sql("""
          insert into purchase_return_item (id, return_id, purchase_item_id, product_id, qty, taxable_paise, cgst_paise, sgst_paise, igst_paise,
            cess_paise, total_paise)
          values (:id, :ret, :line, :product, :qty, :taxable, :cgst, :sgst, :igst, :cess, :total)""")
          .param("id", i.id()).param("ret", r.id()).param("line", i.purchaseItemId()).param("product", i.productId()).param("qty", i.qty())
          .param("taxable", x.taxablePaise()).param("cgst", x.cgstPaise()).param("sgst", x.sgstPaise()).param("igst", x.igstPaise())
          .param("cess", x.cessPaise()).param("total", x.totalPaise()).update();
    }
  }

  /** Debit notes, newest first, with a bounded total. */
  public Page<Return> page(Filter f, int page, int size) {
    List<String> c = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();
    if (f.purchaseId() != null) {
      c.add("r.purchase_id = :purchase");
      params.put("purchase", f.purchaseId());
    }
    if (f.supplierId() != null) {
      c.add("p.supplier_id = :supplier");
      params.put("supplier", f.supplierId());
    }
    if (f.from() != null) {
      c.add("r.created_at >= :from");
      params.put("from", Sql.ts(f.from()));
    }
    if (f.toExclusive() != null) {
      c.add("r.created_at < :to");
      params.put("to", Sql.ts(f.toExclusive()));
    }
    String where = c.isEmpty() ? "" : " where " + String.join(" and ", c);
    var q = jdbc.sql(SELECT + where + " order by r.created_at desc, r.id desc limit " + (size + 1) + " offset " + (long) page * size);
    params.forEach(q::param);
    List<Return> fetched = q.query(PurchaseReturnRepository::ret).list();
    return BoundedCount.assemble(fetched, page, size,
        () -> Sql.boundedCount(jdbc, "purchase_return r join purchase p on p.id = r.purchase_id" + where, params));
  }
}
