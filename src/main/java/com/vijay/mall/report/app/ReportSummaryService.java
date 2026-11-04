package com.vijay.mall.report.app;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.error.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales for a date range (week, month, quarter or custom), online orders and counter bills together. Days are in the
 * shop's time zone. salesPaise and orders add both channels; refunds at the counter are listed apart, not subtracted.
 */
@Service
public class ReportSummaryService {
  public record DayRow(LocalDate date, long salesPaise, long orders) {}

  /** qty counts units only: items weighed at the counter add to salesPaise but not to qty. */
  public record TopProduct(UUID productId, String name, long qty, long salesPaise) {}

  @Schema(name = "SalesSummary")

  public record Summary(LocalDate from, LocalDate to, long salesPaise, long orders, long onlineSalesPaise,
                        long onlineOrders, long posSalesPaise, long posBills, long posRefundsPaise, long coinsIssued,
                        List<DayRow> byDay, List<TopProduct> topProducts, long creditGivenPaise,
                        long creditRecoveredPaise, long outstandingCreditPaise) {}

  public record Range(LocalDate from, LocalDate to) {}

  static final ZoneId SHOP = ZoneId.of("Asia/Kolkata");
  static final int MAX_DAYS = 366;
  private static final String COUNTED = "status NOT IN ('CANCELLED','PAYMENT_PENDING')";

  @PersistenceContext
  private EntityManager em;

  private final Clock clock;

  public ReportSummaryService(Clock clock) { this.clock = clock; }

  /** Pure: picks the dates. An explicit from wins; otherwise period (week 7 days, month 30, quarter 90) ending today. */
  public static Range range(String period, LocalDate from, LocalDate to, LocalDate today) {
    LocalDate end = to == null ? today : to;
    LocalDate start = from;
    if (start == null) {
      String p = period == null || period.isBlank() ? "week" : period.trim().toLowerCase();
      int days = switch (p) {
        case "week" -> 7;
        case "month" -> 30;
        case "quarter" -> 90;
        default -> throw ApiException.validation("period", "must be week, month or quarter");
      };
      end = today;
      start = today.minusDays(days - 1L);
    }
    if (end.isBefore(start)) throw ApiException.validation("to", "must not be before from");
    if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
      throw ApiException.validation("to", "range is limited to " + MAX_DAYS + " days");
    }
    return new Range(start, end);
  }

  @Transactional(readOnly = true)
  public Summary summary(String period, LocalDate from, LocalDate to) {
    Range r = range(period, from, to, clock.instant().atZone(SHOP).toLocalDate());
    Instant f = r.from().atStartOfDay(SHOP).toInstant();
    Instant t = r.to().plusDays(1).atStartOfDay(SHOP).toInstant();

    Map<LocalDate, long[]> online = byDay("SELECT (placed_at AT TIME ZONE 'Asia/Kolkata')::date, "
        + "COALESCE(SUM(total_paise), 0), count(*) FROM orders WHERE placed_at >= :f AND placed_at < :t AND "
        + COUNTED + " GROUP BY 1", f, t);
    Map<LocalDate, long[]> pos = byDay("SELECT (created_at AT TIME ZONE 'Asia/Kolkata')::date, "
        + "COALESCE(SUM(total_paise), 0), count(*) FROM pos_sale WHERE created_at >= :f AND created_at < :t GROUP BY 1", f, t);

    List<DayRow> days = new ArrayList<>();
    long onSales = 0, onOrders = 0, posSales = 0, posBills = 0;
    for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
      long[] o = online.getOrDefault(d, new long[2]);
      long[] p = pos.getOrDefault(d, new long[2]);
      onSales += o[0];
      onOrders += o[1];
      posSales += p[0];
      posBills += p[1];
      days.add(new DayRow(d, o[0] + p[0], o[1] + p[1]));
    }
    long refunds = scalar("SELECT COALESCE(SUM(refund_paise), 0) FROM pos_return WHERE created_at >= :f AND created_at < :t", f, t);
    long coins = scalar("SELECT COALESCE(SUM(coins), 0) FROM coin_ledger WHERE kind = 'EARN' AND created_at >= :f AND created_at < :t", f, t);
    return new Summary(r.from(), r.to(), onSales + posSales, onOrders + posBills, onSales, onOrders, posSales, posBills,
        refunds, coins, days, topProducts(f, t), creditSum("SALE", true, f, t), creditSum("PAYMENT", false, f, t),
        ((Number) em.createNativeQuery("SELECT COALESCE(SUM(balance_paise), 0) FROM credit_account").getSingleResult()).longValue());
  }

  @SuppressWarnings("unchecked")
  private Map<LocalDate, long[]> byDay(String sql, Instant f, Instant t) {
    List<Object[]> rows = em.createNativeQuery(sql).setParameter("f", f).setParameter("t", t).getResultList();
    Map<LocalDate, long[]> out = new LinkedHashMap<>();
    for (Object[] row : rows) {
      out.put(toDate(row[0]), new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private List<TopProduct> topProducts(Instant f, Instant t) {
    List<Object[]> online = em.createNativeQuery(
            "SELECT oi.product_id, MAX(oi.name_snapshot), SUM(oi.qty), SUM(oi.line_total_paise) FROM order_item oi "
                + "JOIN orders o ON o.id = oi.order_id WHERE o.placed_at >= :f AND o.placed_at < :t AND o."
                + COUNTED + " GROUP BY oi.product_id ORDER BY 4 DESC LIMIT 100")
        .setParameter("f", f).setParameter("t", t).getResultList();
    List<Object[]> counter = em.createNativeQuery(
            "SELECT i.product_id, MAX(i.name), SUM(CASE WHEN i.sold_by = 'UNIT' THEN i.qty ELSE 0 END), "
                + "SUM(i.net_paise) FROM pos_sale_item i JOIN pos_sale s ON s.id = i.sale_id "
                + "WHERE s.created_at >= :f AND s.created_at < :t GROUP BY i.product_id ORDER BY 4 DESC LIMIT 100")
        .setParameter("f", f).setParameter("t", t).getResultList();
    Map<UUID, TopProduct> merged = new LinkedHashMap<>();
    for (Object[] row : java.util.stream.Stream.concat(online.stream(), counter.stream()).toList()) {
      UUID id = row[0] instanceof UUID u ? u : UUID.fromString(row[0].toString());
      TopProduct add = new TopProduct(id, (String) row[1], ((Number) row[2]).longValue(), ((Number) row[3]).longValue());
      merged.merge(id, add, (a, b) -> new TopProduct(a.productId(), a.name(), a.qty() + b.qty(), a.salesPaise() + b.salesPaise()));
    }
    return merged.values().stream().sorted(Comparator.comparingLong(TopProduct::salesPaise).reversed()).limit(10).toList();
  }

  /** Credit moved by one kind of ledger entry in the period: money put on accounts (up) or taken off (down). */
  private long creditSum(String kind, boolean up, Instant f, Instant t) {
    String sum = up ? "SUM(delta_paise)" : "SUM(-delta_paise)";
    return scalar("SELECT COALESCE(" + sum + ", 0) FROM credit_ledger WHERE kind = '" + kind
        + "' AND created_at >= :f AND created_at < :t", f, t);
  }

  private long scalar(String sql, Instant f, Instant t) {
    return ((Number) em.createNativeQuery(sql).setParameter("f", f).setParameter("t", t).getSingleResult()).longValue();
  }

  /** Native date columns come back as LocalDate, java.sql.Date or text depending on the driver setup. */
  static LocalDate toDate(Object o) {
    if (o instanceof LocalDate d) return d;
    if (o instanceof java.sql.Date d) return d.toLocalDate();
    if (o instanceof java.util.Date d) return new java.sql.Date(d.getTime()).toLocalDate();
    return LocalDate.parse(o.toString());
  }
}
