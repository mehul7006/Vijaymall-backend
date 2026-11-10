package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.common.tax.GstRateJson;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only report queries over purchases, their payments and returns. */
@Repository
public class PurchaseReportRepository {
  /** One purchase line in the register. */
  public record RegisterRow(UUID purchaseId, String invoiceNo, LocalDate invoiceDate, UUID supplierId, String supplierName, String gstMode,
                            UUID productId, int qty, int freeQty, BigDecimal unitCostPaise, long discountPaise, long taxablePaise, @GstRateJson BigDecimal gstPercent,
                            long cgstPaise, long sgstPaise, long igstPaise, long cessPaise, long lineTotalPaise, BigDecimal landedUnitCost) {}

  /** Input tax of one GST mode and rate, net of returns. */
  public record GstRow(String gstMode, @GstRateJson BigDecimal gstPercent, long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise) {}

  /** What one supplier gave in discounts. */
  public record DiscountRow(UUID supplierId, String supplierName, long purchaseCount, long grossPaise, long lineDiscountPaise, long docDiscountPaise) {}

  /** What one supplier is owed by age of the invoice; {@code creditPaise} is the (negative) total of purchases the supplier owes back. */
  public record AgeingRow(UUID supplierId, String supplierName, long days0to30Paise, long days31to60Paise, long days61PlusPaise, long creditPaise,
                          long totalDuePaise) {}

  private final JdbcClient jdbc;

  PurchaseReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** The purchase lines in a date range (by invoice date), newest first, with a bounded total. */
  public Page<RegisterRow> register(LocalDate from, LocalDate to, UUID supplierId, UUID productId, BigDecimal gstRate, int page, int size) {
    List<String> c = new ArrayList<>(List.of("p.invoice_date >= :from", "p.invoice_date <= :to"));
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("from", from);
    params.put("to", to);
    if (supplierId != null) {
      c.add("p.supplier_id = :supplier");
      params.put("supplier", supplierId);
    }
    if (productId != null) {
      c.add("i.product_id = :product");
      params.put("product", productId);
    }
    if (gstRate != null) {
      c.add("i.gst_percent = :rate");
      params.put("rate", gstRate);
    }
    String fromWhere = "purchase_item i join purchase p on p.id = i.purchase_id where " + String.join(" and ", c);
    var q = jdbc.sql("""
        select p.id as purchase_id, p.invoice_no, p.invoice_date, p.supplier_id, p.supplier_name, p.gst_mode, i.product_id, i.qty, i.free_qty,
          i.unit_cost_paise, i.line_discount_paise + i.doc_discount_share_paise as discount_paise, i.taxable_paise, i.gst_percent, i.cgst_paise,
          i.sgst_paise, i.igst_paise, i.cess_paise, i.line_total_paise, i.landed_unit_cost
        from\s""" + fromWhere + " order by p.invoice_date desc, p.created_at desc, i.id limit " + (size + 1) + " offset " + (long) page * size);
    params.forEach(q::param);
    List<RegisterRow> fetched = q.query((r, n) -> new RegisterRow(r.getObject("purchase_id", UUID.class), r.getString("invoice_no"),
        r.getObject("invoice_date", LocalDate.class), r.getObject("supplier_id", UUID.class), r.getString("supplier_name"), r.getString("gst_mode"),
        r.getObject("product_id", UUID.class), r.getInt("qty"), r.getInt("free_qty"), r.getBigDecimal("unit_cost_paise"), r.getLong("discount_paise"),
        r.getLong("taxable_paise"), GstRates.normalize(r.getBigDecimal("gst_percent")), r.getLong("cgst_paise"), r.getLong("sgst_paise"), r.getLong("igst_paise"),
        r.getLong("cess_paise"), r.getLong("line_total_paise"), r.getBigDecimal("landed_unit_cost"))).list();
    return BoundedCount.assemble(fetched, page, size, () -> Sql.boundedCount(jdbc, fromWhere, params));
  }

  /**
   * Input tax by GST mode and rate: purchase lines and freight by invoice date, minus debit notes by the day they were made
   * ({@code from} inclusive, {@code toExclusive} is the start of the day after the last day).
   */
  public List<GstRow> inputGst(LocalDate from, LocalDate to, Instant fromAt, Instant toExclusive) {
    return jdbc.sql("""
        select gst_mode, gst_percent, sum(taxable) as taxable, sum(cgst) as cgst, sum(sgst) as sgst, sum(igst) as igst, sum(cess) as cess from (
          select p.gst_mode, i.gst_percent, i.taxable_paise as taxable, i.cgst_paise as cgst, i.sgst_paise as sgst, i.igst_paise as igst,
            i.cess_paise as cess
          from purchase_item i join purchase p on p.id = i.purchase_id where p.invoice_date >= :from and p.invoice_date <= :to
          union all
          select p.gst_mode, p.freight_gst_percent, p.freight_paise,
            case when p.inter_state then 0 else p.freight_gst_paise / 2 end,
            case when p.inter_state then 0 else p.freight_gst_paise - p.freight_gst_paise / 2 end,
            case when p.inter_state then p.freight_gst_paise else 0 end, 0
          from purchase p where p.invoice_date >= :from and p.invoice_date <= :to and p.freight_paise > 0
          union all
          select p.gst_mode, i.gst_percent, -ri.taxable_paise, -ri.cgst_paise, -ri.sgst_paise, -ri.igst_paise, -ri.cess_paise
          from purchase_return_item ri join purchase_return r on r.id = ri.return_id join purchase_item i on i.id = ri.purchase_item_id
            join purchase p on p.id = r.purchase_id
          where r.created_at >= :fromAt and r.created_at < :toAt
        ) t group by gst_mode, gst_percent order by gst_mode, gst_percent""")
        .param("from", from).param("to", to).param("fromAt", Sql.ts(fromAt)).param("toAt", Sql.ts(toExclusive))
        .query((r, n) -> new GstRow(r.getString("gst_mode"), GstRates.normalize(r.getBigDecimal("gst_percent")), r.getLong("taxable"), r.getLong("cgst"), r.getLong("sgst"),
            r.getLong("igst"), r.getLong("cess"))).list();
  }

  /** Discounts by supplier for purchases in the date range (by invoice date), biggest first. */
  public List<DiscountRow> discounts(LocalDate from, LocalDate to) {
    return jdbc.sql("""
        select p.supplier_id, s.name, count(*) as cnt, sum(p.taxable_paise + p.line_discount_paise + p.doc_discount_paise) as gross,
          sum(p.line_discount_paise) as line_discount, sum(p.doc_discount_paise) as doc_discount
        from purchase p join supplier s on s.id = p.supplier_id where p.invoice_date >= :from and p.invoice_date <= :to
        group by p.supplier_id, s.name having sum(p.line_discount_paise + p.doc_discount_paise) > 0
        order by sum(p.line_discount_paise + p.doc_discount_paise) desc, s.name limit 500""").param("from", from).param("to", to)
        .query((r, n) -> new DiscountRow(r.getObject("supplier_id", UUID.class), r.getString("name"), r.getLong("cnt"), r.getLong("gross"),
            r.getLong("line_discount"), r.getLong("doc_discount"))).list();
  }

  /** What each supplier is owed as of a day, by age of the invoice (0-30, 31-60, 61 days and more), most owed first. */
  public List<AgeingRow> ageing(LocalDate asOf) {
    return jdbc.sql("""
        select p.supplier_id, s.name,
          coalesce(sum(p.due_paise) filter (where p.due_paise > 0 and :asOf - p.invoice_date <= 30), 0) as d30,
          coalesce(sum(p.due_paise) filter (where p.due_paise > 0 and :asOf - p.invoice_date between 31 and 60), 0) as d60,
          coalesce(sum(p.due_paise) filter (where p.due_paise > 0 and :asOf - p.invoice_date > 60), 0) as d61,
          coalesce(sum(p.due_paise) filter (where p.due_paise < 0), 0) as credit, sum(p.due_paise) as total
        from purchase p join supplier s on s.id = p.supplier_id where p.due_paise <> 0
        group by p.supplier_id, s.name order by total desc, s.name limit 500""").param("asOf", asOf)
        .query((r, n) -> new AgeingRow(r.getObject("supplier_id", UUID.class), r.getString("name"), r.getLong("d30"), r.getLong("d60"),
            r.getLong("d61"), r.getLong("credit"), r.getLong("total"))).list();
  }
}
