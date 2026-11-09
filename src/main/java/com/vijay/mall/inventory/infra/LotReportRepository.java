package com.vijay.mall.inventory.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Read-only reports over stock lots, allocations and sales: lot lists, expiry, valuation, margin and the drift check. */
@Repository
public class LotReportRepository {
  /** A lot with its product name. {@code sold} is received minus remaining. */
  public record LotRow(UUID id, UUID productId, String productName, Instant receivedAt, int qtyReceived, int qtyRemaining, BigDecimal unitCost,
                       boolean costKnown, Long mrpPaise, Long pricePaise, String batchNo, LocalDate expiryDate, String source, UUID purchaseItemId) {
    /** Units already used from the lot. */
    public int sold() { return qtyReceived - qtyRemaining; }
  }

  /** Stock value of a product or category: units, value at cost, and how many of the units have no known cost. */
  public record ValuationRow(UUID id, String name, long qty, long valuePaise, long costUnknownQty) {}

  /** The whole stock value. */
  public record ValuationTotal(long qty, long valuePaise, long costUnknownProducts, long costUnknownQty) {}

  /** A group of the margin report: revenue before tax, cost of the goods, margin and margin percent (null when revenue is zero). */
  public record MarginRow(String key, String name, long revenuePaise, long costPaise, long marginPaise, Double marginPercent, long uncostedLines) {}

  /** A product whose lots do not add up to its stock row. */
  public record Drift(UUID productId, String name, int onHand, long inLots) {}

  private final JdbcClient jdbc;

  LotReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static final String LOT_SELECT = "select l.*, p.name_en from stock_lot l join product p on p.id = l.product_id ";

  private static LotRow row(ResultSet r) throws SQLException {
    var l = LotRepository.lot(r);
    return new LotRow(l.id(), l.productId(), r.getString("name_en"), l.receivedAt(), l.qtyReceived(), l.qtyRemaining(), l.unitCost(), l.costKnown(),
        l.mrpPaise(), l.pricePaise(), l.batchNo(), l.expiryDate(), l.source(), r.getObject("purchase_item_id", UUID.class));
  }

  /** The lots of a product, newest first; {@code onlyOpen} keeps those with stock. */
  public Page<LotRow> lotsOf(UUID productId, boolean onlyOpen, int page, int size) {
    String where = "where l.product_id = :p" + (onlyOpen ? " and l.qty_remaining > 0" : "");
    List<LotRow> rows = jdbc.sql(LOT_SELECT + where + " order by l.received_at desc, l.id limit :lim offset :off").param("p", productId)
        .param("lim", size + 1).param("off", (long) page * size).query((r, _) -> row(r)).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from stock_lot l " + where + " limit "
        + (BoundedCount.CAP + 1) + ") c").param("p", productId).query(Long.class).single());
  }

  /** Lots with stock that expire on or before {@code until} (expired ones included), soonest first, at most {@code limit}. */
  public List<LotRow> expiring(LocalDate until, int limit) {
    return jdbc.sql(LOT_SELECT + "where l.qty_remaining > 0 and l.expiry_date is not null and l.expiry_date <= :until order by l.expiry_date, l.id limit :lim")
        .param("until", until).param("lim", limit).query((r, _) -> row(r)).list();
  }

  /** Stock value by product or category (largest first). */
  public Page<ValuationRow> valuation(boolean byCategory, int page, int size) {
    String key = byCategory ? "c.id" : "p.id";
    String name = byCategory ? "c.name_en" : "p.name_en";
    String join = byCategory ? "join category c on c.id = p.category_id" : "";
    List<ValuationRow> rows = jdbc.sql("select " + key + " as id, " + name + " as name, sum(l.qty_remaining) as qty, "
        + "round(sum(l.qty_remaining * l.unit_cost)) as value, coalesce(sum(l.qty_remaining) filter (where not l.cost_known), 0) as unknown "
        + "from stock_lot l join product p on p.id = l.product_id " + join + " where l.qty_remaining > 0 group by " + key + ", " + name
        + " order by value desc, " + key + " limit :lim offset :off").param("lim", size + 1).param("off", (long) page * size)
        .query((r, _) -> new ValuationRow(r.getObject("id", UUID.class), r.getString("name"), r.getLong("qty"), r.getLong("value"), r.getLong("unknown"))).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql("select count(*) from (select 1 from stock_lot l join product p on p.id = l.product_id "
        + join + " where l.qty_remaining > 0 group by " + key + " limit " + (BoundedCount.CAP + 1) + ") g").query(Long.class).single());
  }

  /** The whole stock value and how much of it has no known cost. */
  public ValuationTotal valuationTotal() {
    return jdbc.sql("select coalesce(sum(qty_remaining), 0) as qty, coalesce(round(sum(qty_remaining * unit_cost)), 0) as value, "
        + "count(distinct product_id) filter (where not cost_known) as products, coalesce(sum(qty_remaining) filter (where not cost_known), 0) as unknown "
        + "from stock_lot where qty_remaining > 0").query((r, _) -> new ValuationTotal(r.getLong("qty"), r.getLong("value"), r.getLong("products"),
        r.getLong("unknown"))).single();
  }

  /**
   * Margin by product, category or day (shop time) for bills and delivered-or-open online orders placed in [from, toExclusive). Revenue is
   * before tax and net of returned units; cost is the lot cost of the goods less the cost of units that went back on the shelf. Lines sold
   * before lots existed (no cost) are counted in {@code uncostedLines} and left out of the money.
   *
   * @param groupBy product, category or day
   */
  public Page<MarginRow> margin(String groupBy, Instant from, Instant toExclusive, int page, int size) {
    String key = switch (groupBy) {
      case "category" -> "c.id::text";
      case "day" -> "to_char((x.at at time zone 'Asia/Kolkata')::date, 'YYYY-MM-DD')";
      default -> "p.id::text";
    };
    String name = switch (groupBy) {
      case "category" -> "c.name_en";
      case "day" -> key;
      default -> "p.name_en";
    };
    String join = "join product p on p.id = x.product_id" + ("category".equals(groupBy) ? " join category c on c.id = p.category_id" : "");
    String order = "day".equals(groupBy) ? "1" : "margin desc, 1";
    String sql = MARGIN_LINES + " select " + key + " as k, " + name + " as n, coalesce(round(sum(x.revenue) filter (where x.cost is not null)), 0) as revenue, "
        + "coalesce(sum(x.cost - x.returned_cost) filter (where x.cost is not null), 0) as cost, "
        + "coalesce(round(sum(x.revenue) filter (where x.cost is not null)), 0) - coalesce(sum(x.cost - x.returned_cost) filter (where x.cost is not null), 0) as margin, "
        + "count(*) filter (where x.cost is null) as uncosted from lines x " + join + " group by 1, 2 order by " + order + " limit :lim offset :off";
    List<MarginRow> rows = jdbc.sql(sql).param("from", LotRepository.ts(from)).param("to", LotRepository.ts(toExclusive)).param("lim", size + 1)
        .param("off", (long) page * size).query((r, _) -> {
          long revenue = r.getLong("revenue");
          long margin = r.getLong("margin");
          return new MarginRow(r.getString("k"), r.getString("n"), revenue, r.getLong("cost"), margin,
              revenue == 0 ? null : Math.round(margin * 10000.0 / revenue) / 100.0, r.getLong("uncosted"));
        }).list();
    return BoundedCount.assemble(rows, page, size, () -> jdbc.sql(MARGIN_LINES + " select count(*) from (select 1 from lines x " + join + " group by "
        + (key) + " limit " + (BoundedCount.CAP + 1) + ") g").param("from", LotRepository.ts(from))
        .param("to", LotRepository.ts(toExclusive)).query(Long.class).single());
  }

  private static final String MARGIN_LINES = """
      with lines as (
        select s.created_at as at, i.product_id, i.cost_paise as cost,
               i.taxable_paise::numeric * (i.qty - i.returned_qty) / i.qty as revenue,
               coalesce((select sum(a.returned_cost_paise) from stock_allocation a where a.ref_type = 'POS_SALE_ITEM' and a.ref_id = i.id), 0) as returned_cost
        from pos_sale_item i join pos_sale s on s.id = i.sale_id
        where s.created_at >= :from and s.created_at < :to
        union all
        select o.placed_at, oi.product_id, oi.cost_paise,
               (oi.line_total_paise - oi.coupon_discount_paise)::numeric * (oi.qty - oi.returned_qty) / oi.qty * 100 / (100 + coalesce(pp.gst_percent, 0)),
               coalesce((select sum(a.returned_cost_paise) from stock_allocation a where a.ref_type = 'ORDER_ITEM' and a.ref_id = oi.id), 0)
        from order_item oi join orders o on o.id = oi.order_id left join product_pos pp on pp.product_id = oi.product_id
        where o.placed_at >= :from and o.placed_at < :to and o.status <> 'CANCELLED' and oi.cost_paise is not null
      )""";

  /** Products whose lots do not add up to their stock row (at most {@code limit}); the list should be empty. */
  public List<Drift> drift(int limit) {
    return jdbc.sql("""
        select i.product_id, p.name_en, i.qty_on_hand, coalesce(s.total, 0) as in_lots
        from inventory i join product p on p.id = i.product_id
        left join (select product_id, sum(qty_remaining) as total from stock_lot where qty_remaining > 0 group by product_id) s on s.product_id = i.product_id
        where i.qty_on_hand <> coalesce(s.total, 0) order by i.product_id limit :lim""").param("lim", limit)
        .query((r, _) -> new Drift(r.getObject(1, UUID.class), r.getString(2), r.getInt(3), r.getLong(4))).list();
  }

  /** Lots past their expiry date that still have stock, oldest first, at most {@code limit}. */
  public List<LotRow> expired(LocalDate today, int limit) {
    return jdbc.sql(LOT_SELECT + "where l.qty_remaining > 0 and l.expiry_date < :today order by l.expiry_date, l.id limit :lim")
        .param("today", today).param("lim", limit).query((r, _) -> row(r)).list();
  }
}
