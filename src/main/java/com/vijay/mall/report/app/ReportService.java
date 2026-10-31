package com.vijay.mall.report.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only numbers for the admin dashboard. "Today" is in the shop's time zone. */
@Service
public class ReportService {
  public record Day(LocalDate date, long salesPaise) {}

  public record LowItem(UUID productId, String name, int qty) {}

  public record Dashboard(long salesTodayPaise, long ordersToday, long newOrders, long coinsIssuedToday,
                          List<Day> last7Days, List<LowItem> lowStock, long posSalesTodayPaise, long posBillsToday,
                          long posRefundsTodayPaise) {}

  private static final ZoneId SHOP = ZoneId.of("Asia/Kolkata");
  private static final String COUNTED = "status NOT IN ('CANCELLED','PAYMENT_PENDING')";

  @PersistenceContext
  private EntityManager em;

  private final InventoryService inventory;
  private final CatalogService catalog;
  private final Clock clock;

  public ReportService(InventoryService inventory, CatalogService catalog, Clock clock) {
    this.inventory = inventory;
    this.catalog = catalog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public Dashboard dashboard() {
    LocalDate today = clock.instant().atZone(SHOP).toLocalDate();
    var from = today.minusDays(6).atStartOfDay(SHOP).toInstant();
    var dayStart = today.atStartOfDay(SHOP).toInstant();

    long sales = scalar("SELECT COALESCE(SUM(total_paise), 0) FROM orders WHERE placed_at >= :d AND " + COUNTED, dayStart);
    long count = scalar("SELECT count(*) FROM orders WHERE placed_at >= :d AND " + COUNTED, dayStart);
    long fresh = ((Number) em.createNativeQuery("SELECT count(*) FROM orders WHERE status = 'PLACED'").getSingleResult()).longValue();
    long coins = scalar("SELECT COALESCE(SUM(coins), 0) FROM coin_ledger WHERE kind = 'EARN' AND created_at >= :d", dayStart);

    long posSales = scalar("SELECT COALESCE(SUM(total_paise), 0) FROM pos_sale WHERE created_at >= :d", dayStart);
    long posBills = scalar("SELECT count(*) FROM pos_sale WHERE created_at >= :d", dayStart);
    long posRefunds = scalar("SELECT COALESCE(SUM(refund_paise), 0) FROM pos_return WHERE created_at >= :d", dayStart);

    return new Dashboard(sales, count, fresh, coins, lastSevenDays(today, from), lowStock(), posSales, posBills, posRefunds);
  }

  /** Always seven entries, oldest first; days without orders show zero. */
  @SuppressWarnings("unchecked")
  private List<Day> lastSevenDays(LocalDate today, java.time.Instant from) {
    List<Object[]> rows = em.createNativeQuery(
            "SELECT (placed_at AT TIME ZONE 'Asia/Kolkata')::date AS d, COALESCE(SUM(total_paise), 0) AS s FROM orders "
                + "WHERE placed_at >= :f AND " + COUNTED + " GROUP BY 1")
        .setParameter("f", from).getResultList();
    Map<LocalDate, Long> byDay = rows.stream().collect(Collectors.toMap(
        r -> ((LocalDate) r[0]), r -> ((Number) r[1]).longValue()));
    return IntStream.rangeClosed(0, 6).mapToObj(i -> today.minusDays(6 - i))
        .map(d -> new Day(d, byDay.getOrDefault(d, 0L))).toList();
  }

  private List<LowItem> lowStock() {
    List<InventoryRepository.Stock> low = inventory.lowStock(10);
    Map<UUID, String> names = catalog.names(low.stream().map(InventoryRepository.Stock::productId).toList());
    return low.stream().map(s -> new LowItem(s.productId(), names.getOrDefault(s.productId(), "?"), s.qty())).toList();
  }

  private long scalar(String sql, java.time.Instant param) {
    return ((Number) em.createNativeQuery(sql).setParameter("d", param).getSingleResult()).longValue();
  }
}
