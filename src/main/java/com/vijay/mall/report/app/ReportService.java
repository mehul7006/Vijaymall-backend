package com.vijay.mall.report.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only numbers for the admin dashboard. "Today" is in the shop's time zone. */
@Service
public class ReportService {
  /**
   * Online sales of one day.
   * @param salesPaise total of that day's counted online orders, in paise
   */
  public record Day(LocalDate date, long salesPaise) {}

  /**
   * A product at or below its stock alert level.
   * @param qty units left
   */
  public record LowItem(UUID productId, String name, int qty) {}

  /**
   * Numbers on the admin dashboard. Online sales exclude cancelled and unpaid orders.
   * @param salesTodayPaise online order value placed today, in paise
   * @param ordersToday online orders placed today
   * @param newOrders online orders currently in {@code PLACED} waiting for staff
   * @param coinsIssuedToday coins earned today
   * @param last7Days seven entries, oldest first
   * @param lowStock up to ten products low on stock
   * @param posSalesTodayPaise counter sales today, in paise
   * @param posBillsToday counter bills today
   * @param posRefundsTodayPaise counter refunds today, in paise
   * @param onlineRefundsTodayPaise refunds given today on returned online orders, in paise; listed apart like the counter refunds,
   *                                not subtracted from the sales figures above
   */
  public record Dashboard(long salesTodayPaise, long ordersToday, long newOrders, long coinsIssuedToday,
                          List<Day> last7Days, List<LowItem> lowStock, long posSalesTodayPaise, long posBillsToday,
                          long posRefundsTodayPaise, long onlineRefundsTodayPaise) {}

  private static final ZoneId SHOP = ZoneId.of("Asia/Kolkata");
  private static final String COUNTED = "status NOT IN ('CANCELLED','PAYMENT_PENDING')";

  @PersistenceContext
  private EntityManager em;

  private final InventoryService inventory;
  private final CatalogService catalog;
  private final Clock clock;

  /** Creates the service. */
  public ReportService(InventoryService inventory, CatalogService catalog, Clock clock) {
    this.inventory = inventory;
    this.catalog = catalog;
    this.clock = clock;
  }

  private AppCaches caches;

  /** Optional (plain unit tests run without it): the dashboard adds up the whole day, so it is kept for 30 seconds. */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  /** Today's figures in the shop's time zone, cached for 30 seconds. */
  @Transactional(readOnly = true)
  public Dashboard dashboard() {
    LocalDate today = clock.instant().atZone(SHOP).toLocalDate();
    return caches == null ? compute(today) : caches.get(AppCaches.Name.DASHBOARD, today, () -> compute(today));
  }

  private Dashboard compute(LocalDate today) {
    var from = today.minusDays(6).atStartOfDay(SHOP).toInstant();
    var dayStart = today.atStartOfDay(SHOP).toInstant();

    long sales = scalar("SELECT COALESCE(SUM(total_paise), 0) FROM orders WHERE placed_at >= :d AND " + COUNTED, dayStart);
    long count = scalar("SELECT count(*) FROM orders WHERE placed_at >= :d AND " + COUNTED, dayStart);
    long fresh = ((Number) em.createNativeQuery("SELECT count(*) FROM orders WHERE status = 'PLACED'").getSingleResult()).longValue();
    long coins = scalar("SELECT COALESCE(SUM(coins), 0) FROM coin_ledger WHERE kind = 'EARN' AND created_at >= :d", dayStart);

    long posSales = scalar("SELECT COALESCE(SUM(total_paise), 0) FROM pos_sale WHERE created_at >= :d", dayStart);
    long posBills = scalar("SELECT count(*) FROM pos_sale WHERE created_at >= :d", dayStart);
    long posRefunds = scalar("SELECT COALESCE(SUM(refund_paise), 0) FROM pos_return WHERE created_at >= :d", dayStart);
    long onlineRefunds = scalar("SELECT COALESCE(SUM(refund_paise), 0) FROM order_return WHERE created_at >= :d", dayStart);

    return new Dashboard(sales, count, fresh, coins, lastSevenDays(today, from), lowStock(), posSales, posBills, posRefunds, onlineRefunds);
  }

  /** Always seven entries, oldest first; days without orders show zero. */
  @SuppressWarnings("unchecked")
  private List<Day> lastSevenDays(LocalDate today, Instant from) {
    List<Object[]> rows = em.createNativeQuery(
            "SELECT (placed_at AT TIME ZONE 'Asia/Kolkata')::date AS d, COALESCE(SUM(total_paise), 0) AS s FROM orders "
                + "WHERE placed_at >= :f AND " + COUNTED + " GROUP BY 1")
        .setParameter("f", from).getResultList();
    Map<LocalDate, Long> byDay = rows.stream().collect(Collectors.toMap(
        r -> toLocalDate(r[0]), r -> ((Number) r[1]).longValue()));
    return IntStream.rangeClosed(0, 6).mapToObj(i -> today.minusDays(6 - i))
        .map(d -> new Day(d, byDay.getOrDefault(d, 0L))).toList();
  }

  /** A native date column comes back as LocalDate or java.sql.Date depending on the driver and Hibernate mapping. */
  private static LocalDate toLocalDate(Object o) {
    return switch (o) {
      case LocalDate d -> d;
      case java.sql.Date d -> d.toLocalDate();
      case java.util.Date d -> new java.sql.Date(d.getTime()).toLocalDate();
      default -> LocalDate.parse(o.toString().substring(0, 10));
    };
  }

  private List<LowItem> lowStock() { return lowStock(10); }

  /** Active products at or below their stock alert level, emptiest first (retired products are left out). */
  @Transactional(readOnly = true)
  public List<LowItem> lowStock(int limit) {
    List<InventoryRepository.Stock> low = inventory.lowStock(Math.max(1, Math.min(limit, 200)));
    Map<UUID, String> names = catalog.names(low.stream().map(InventoryRepository.Stock::productId).toList());
    return low.stream().map(s -> new LowItem(s.productId(), names.getOrDefault(s.productId(), "?"), s.qty())).toList();
  }

  private long scalar(String sql, Instant param) {
    return ((Number) em.createNativeQuery(sql).setParameter("d", param).getSingleResult()).longValue();
  }
}
