package com.vijay.mall.pos.app;

import com.vijay.mall.pos.app.PosViews.DayReport;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.pos.domain.PaymentRules;
import com.vijay.mall.pos.infra.PosReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The cash-up: what was sold and refunded in a day, by payment mode, and the cash that should be in the drawer. */
@Service
public class PosReportService {
  private final PosReportRepository repo;
  private final Clock clock;

  public PosReportService(PosReportRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  /** {@code cashierId} null means every cashier; {@code date} null means today (shop time). */
  @Transactional(readOnly = true)
  public DayReport day(LocalDate date, UUID cashierId) {
    LocalDate d = date == null ? clock.instant().atZone(InvoiceNumbers.SHOP_ZONE).toLocalDate() : date;
    Instant from = d.atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant();
    Instant to = d.plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant();
    var sales = repo.sales(from, to, cashierId);
    var returns = repo.returns(from, to, cashierId);
    Map<String, Long> paid = allModes(repo.paidByMode(from, to, cashierId));
    Map<String, Long> refunded = allModes(repo.refundedByMode(from, to, cashierId));
    return new DayReport(d, sales.bills(), sales.totalPaise(), sales.taxablePaise(), sales.cgstPaise(), sales.sgstPaise(),
        sales.coinsUsed(), returns.notes(), returns.refundPaise(), sales.totalPaise() - returns.refundPaise(), paid, refunded,
        paid.get("CASH") - refunded.get("CASH"));
  }

  private static Map<String, Long> allModes(Map<String, Long> found) {
    return PaymentRules.MODES.stream().sorted().collect(Collectors.toMap(m -> m, m -> found.getOrDefault(m, 0L), (a, b) -> a,
        LinkedHashMap::new));
  }
}
