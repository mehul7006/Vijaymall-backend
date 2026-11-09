package com.vijay.mall.report.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.persistence.BoundedCount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit (udhar) reports for the owner: what is owed and by whom, how much credit was given and recovered in a period,
 * how recovery came in (cash / UPI / card), and how bills were paid. Days are in the shop's time zone.
 */
@Service
public class CreditReportService {
  /**
   * lastSaleAt / lastPaymentAt are null when the customer never had one.
   * @param availablePaise what the customer may still take on credit: the limit minus the balance, never below zero
   * @param blocked whether the customer is blocked (no credit can be given whatever the limit)
   */
  public record Debtor(UUID customerId, String name, String mobile, long balancePaise, long limitPaise, long availablePaise,
                       boolean blocked, Instant lastSaleAt, Instant lastPaymentAt) {}

  /**
   * How many counter bills were paid by one payment type and their total.
   * @param type the bill's payment type, for example cash or credit
   * @param totalPaise sum of the bills' totals, in paise
   */
  public record TypeRow(String type, long bills, long totalPaise) {}

  /**
   * Credit given and recovered on one day.
   * @param creditGivenPaise credit put on accounts by bills that day, in paise
   * @param recoveredPaise credit customers paid off that day, in paise
   */
  @Schema(name = "CreditDayRow")

  public record DayRow(LocalDate date, long creditGivenPaise, long recoveredPaise) {}

  /**
   * givenPaise: put on accounts by bills. returnedPaise: cancelled because goods came back. recoveredPaise: customers
   * paid it off. writtenOffPaise / addedPaise: admin corrections down / up.
   */
  @Schema(name = "CreditReport")
  public record Report(LocalDate from, LocalDate to, long outstandingPaise, long customersWithDues, long givenPaise,
                       long returnedPaise, long recoveredPaise, long writtenOffPaise, long addedPaise,
                       Map<String, Long> recoveredByMode, List<TypeRow> salesByPaymentType, List<DayRow> byDay,
                       List<Debtor> topDebtors) {}

  private static final String SHOP_ZONE_SQL = "'Asia/Kolkata'";
  private static final List<String> MODES = List.of("CASH", "UPI", "CARD");

  @PersistenceContext
  private EntityManager em;

  private final Clock clock;

  /** Creates the service. */
  public CreditReportService(Clock clock) { this.clock = clock; }

  /**
   * Credit given, returned, recovered and corrected in the period, what is still owed in total, recovery by cash/UPI/card, bills by payment type, a row per day and the biggest debtors.
   * @param period {@code week}, {@code month} or {@code quarter} (default week); ignored when {@code from} is given
   * @param from first day, or {@code null}
   * @param to last day, included, or {@code null} for today
   */
  @Transactional(readOnly = true)
  public Report report(String period, LocalDate from, LocalDate to) {
    ReportSummaryService.Range r = ReportSummaryService.range(period, from, to,
        clock.instant().atZone(ReportSummaryService.SHOP).toLocalDate());
    Instant f = r.from().atStartOfDay(ReportSummaryService.SHOP).toInstant();
    Instant t = r.to().plusDays(1).atStartOfDay(ReportSummaryService.SHOP).toInstant();

    Object[] owed = (Object[]) em.createNativeQuery(
        "SELECT COALESCE(SUM(balance_paise), 0), COUNT(*) FILTER (WHERE balance_paise > 0) FROM credit_account").getSingleResult();

    long given = 0, returned = 0, recovered = 0, writtenOff = 0, added = 0;
    for (Object[] row : rows("SELECT kind, COALESCE(SUM(CASE WHEN delta_paise > 0 THEN delta_paise ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN delta_paise < 0 THEN -delta_paise ELSE 0 END), 0) FROM credit_ledger "
        + "WHERE created_at >= :f AND created_at < :t GROUP BY kind", f, t)) {
      long up = num(row[1]);
      long down = num(row[2]);
      switch ((String) row[0]) {
        case "SALE" -> given = up;
        case "RETURN" -> returned = down;
        case "PAYMENT" -> recovered = down;
        default -> { writtenOff = down; added = up; }
      }
    }

    Map<String, Long> byMode = new LinkedHashMap<>();
    MODES.forEach(m -> byMode.put(m, 0L));
    for (Object[] row : rows("SELECT l.mode, COALESCE(SUM(l.amount_paise), 0) FROM credit_payment_line l "
        + "JOIN credit_payment p ON p.id = l.payment_id WHERE p.created_at >= :f AND p.created_at < :t GROUP BY l.mode", f, t)) {
      byMode.put((String) row[0], num(row[1]));
    }

    List<TypeRow> types = rows("SELECT payment_type, COUNT(*), COALESCE(SUM(total_paise), 0) FROM pos_sale "
        + "WHERE created_at >= :f AND created_at < :t GROUP BY payment_type ORDER BY 3 DESC", f, t).stream()
        .map(row -> new TypeRow((String) row[0], num(row[1]), num(row[2]))).toList();

    Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
    for (Object[] row : rows("SELECT (created_at AT TIME ZONE " + SHOP_ZONE_SQL + ")::date, "
        + "COALESCE(SUM(CASE WHEN kind = 'SALE' THEN delta_paise ELSE 0 END), 0), "
        + "COALESCE(SUM(CASE WHEN kind = 'PAYMENT' THEN -delta_paise ELSE 0 END), 0) FROM credit_ledger "
        + "WHERE created_at >= :f AND created_at < :t GROUP BY 1", f, t)) {
      perDay.put(ReportSummaryService.toDate(row[0]), new long[] {num(row[1]), num(row[2])});
    }
    List<DayRow> days = new ArrayList<>();
    for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
      long[] v = perDay.getOrDefault(d, new long[2]);
      days.add(new DayRow(d, v[0], v[1]));
    }

    return new Report(r.from(), r.to(), num(owed[0]), num(owed[1]), given, returned, recovered, writtenOff, added, byMode,
        types, days, debtors(true, false, null, 0, 20).items());
  }

  /**
   * Customers with a credit account, biggest balance first. The total is exact (see {@link BoundedCount}).
   * @param outstandingOnly leaves out those who owe nothing
   * @param eligibleOnly keeps only customers who have a credit limit above zero
   * @param q part of the name or mobile number, any case; blank for no search
   */
  @Transactional(readOnly = true)
  @SuppressWarnings("unchecked")
  public Page<Debtor> debtors(boolean outstandingOnly, boolean eligibleOnly, String q, int page, int size) {
    int p = Math.max(0, page);
    int n = Page.clampSize(size);
    if (q != null && q.length() > 100) throw ApiException.validation("q", "must be at most 100 characters");
    StringBuilder from = new StringBuilder("credit_account a JOIN users u ON u.id = a.user_id WHERE 1 = 1");
    Map<String, Object> params = new HashMap<>();
    if (outstandingOnly) from.append(" AND a.balance_paise > 0");
    if (eligibleOnly) from.append(" AND a.limit_paise > 0");
    if (q != null && !q.isBlank()) {
      from.append(" AND (lower(coalesce(u.name, '')) LIKE :pattern OR coalesce(u.mobile, '') LIKE :pattern)");
      params.put("pattern", "%" + q.trim().toLowerCase().replace("%", "").replace("_", "") + "%");
    }
    var query = em.createNativeQuery("SELECT a.user_id, u.name, u.mobile, a.balance_paise, a.limit_paise, u.status = 'BLOCKED', "
        + "(SELECT MAX(l.created_at) FROM credit_ledger l WHERE l.user_id = a.user_id AND l.kind = 'SALE'), "
        + "(SELECT MAX(l.created_at) FROM credit_ledger l WHERE l.user_id = a.user_id AND l.kind = 'PAYMENT') "
        + "FROM " + from + " ORDER BY a.balance_paise DESC, u.name, a.user_id LIMIT :n OFFSET :o");
    params.forEach(query::setParameter);
    List<Object[]> found = query.setParameter("n", n + 1).setParameter("o", p * n).getResultList();
    List<Debtor> items = found.stream().map(row -> {
      long balance = num(row[3]);
      long limit = num(row[4]);
      return new Debtor(toUuid(row[0]), (String) row[1], (String) row[2], balance, limit, Math.max(0, limit - balance),
          Boolean.TRUE.equals(row[5]), toInstant(row[6]), toInstant(row[7]));
    }).toList();
    return BoundedCount.assemble(items, p, n, () -> BoundedCount.count(em, from.toString(), params));
  }

  @SuppressWarnings("unchecked")
  private List<Object[]> rows(String sql, Instant f, Instant t) {
    return em.createNativeQuery(sql).setParameter("f", f).setParameter("t", t).getResultList();
  }

  private static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

  private static UUID toUuid(Object o) { return o instanceof UUID u ? u : UUID.fromString(o.toString()); }

  /** Timestamps from native queries come back as Instant, OffsetDateTime or java.sql.Timestamp. */
  static Instant toInstant(Object o) {
    if (o == null) return null;
    if (o instanceof Instant i) return i;
    if (o instanceof OffsetDateTime d) return d.toInstant();
    if (o instanceof Date d) return d.toInstant();
    return Instant.parse(o.toString());
  }
}
