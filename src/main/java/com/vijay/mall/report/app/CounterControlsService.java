package com.vijay.mall.report.app;

import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.infra.PosReportRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loss-prevention view of the counter: for each cashier how much they sold, how much they discounted by hand, how much they
 * gave back as returns, and how far their cash drawer was out when they closed a shift. The numbers an owner scans to spot a
 * cashier who discounts, refunds or comes up short far more than the others.
 */
@Service
public class CounterControlsService {
  /** The percentages are of that cashier's own sales, to one decimal; shiftVariancePaise below zero means the drawer was short. */
  public record Row(UUID cashierId, String name, long bills, long salesPaise, long manualDiscounts, long manualDiscountPaise,
                    double discountPercentOfSales, long returns, long refundsPaise, double refundPercentOfSales, long shiftsClosed,
                    long shiftVariancePaise) {}

  /**
   * The counter-controls report for a period.
   * @param from first day, in the shop's time zone
   * @param to last day, included
   * @param cashiers one row per cashier who sold, took returns or closed a shift, biggest sales first
   */
  public record Report(LocalDate from, LocalDate to, List<Row> cashiers) {}

  private final PosReportRepository repo;
  private final UserRepository users;
  private final Clock clock;

  /** Creates the service. */
  public CounterControlsService(PosReportRepository repo, UserRepository users, Clock clock) {
    this.repo = repo;
    this.users = users;
    this.clock = clock;
  }

  /** {@code part} as a percentage of {@code whole} to one decimal; 0 when {@code whole} is not positive. */
  public static double percent(long part, long whole) {
    return whole <= 0 ? 0.0 : Math.round(part * 1000.0 / whole) / 10.0;
  }

  /**
   * Builds the report for the period (week, month, quarter, or explicit from/to days; same rules as the sales summary).
   */
  @Transactional(readOnly = true)
  public Report report(String period, LocalDate from, LocalDate to) {
    LocalDate today = clock.instant().atZone(InvoiceNumbers.SHOP_ZONE).toLocalDate();
    var range = ReportSummaryService.range(period, from, to, today);
    var f = range.from().atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant();
    var t = range.to().plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant();

    Map<UUID, PosReportRepository.CashierSales> sales = repo.salesByCashier(f, t).stream()
        .collect(Collectors.toMap(PosReportRepository.CashierSales::cashierId, Function.identity()));
    Map<UUID, PosReportRepository.CashierReturns> returns = repo.returnsByCashier(f, t).stream()
        .collect(Collectors.toMap(PosReportRepository.CashierReturns::cashierId, Function.identity()));
    Map<UUID, PosReportRepository.CashierShifts> shifts = repo.shiftsByCashier(f, t).stream()
        .collect(Collectors.toMap(PosReportRepository.CashierShifts::cashierId, Function.identity()));

    Set<UUID> ids = new HashSet<>(sales.keySet());
    ids.addAll(returns.keySet());
    ids.addAll(shifts.keySet());
    Map<UUID, User> people = users.findAllByIds(ids).stream().collect(Collectors.toMap(User::id, Function.identity()));

    List<Row> rows = ids.stream().map(id -> {
      var s = sales.get(id);
      var r = returns.get(id);
      var h = shifts.get(id);
      long total = s == null ? 0 : s.totalPaise();
      long discount = s == null ? 0 : s.manualDiscountPaise();
      long refunds = r == null ? 0 : r.refundPaise();
      User u = people.get(id);
      String name = u == null ? "?" : u.name() != null && !u.name().isBlank() ? u.name() : u.email();
      return new Row(id, name, s == null ? 0 : s.bills(), total, s == null ? 0 : s.manualDiscounts(), discount,
          percent(discount, total), r == null ? 0 : r.notes(), refunds, percent(refunds, total), h == null ? 0 : h.closed(),
          h == null ? 0 : h.variancePaise());
    }).sorted(Comparator.comparingLong(Row::salesPaise).reversed()).toList();
    return new Report(range.from(), range.to(), rows);
  }
}
