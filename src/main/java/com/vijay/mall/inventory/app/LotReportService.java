package com.vijay.mall.inventory.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.infra.LotReportRepository;
import com.vijay.mall.inventory.infra.LotReportRepository.Drift;
import com.vijay.mall.inventory.infra.LotReportRepository.LotRow;
import com.vijay.mall.inventory.infra.LotReportRepository.MarginRow;
import com.vijay.mall.inventory.infra.LotReportRepository.ValuationRow;
import com.vijay.mall.inventory.infra.LotReportRepository.ValuationTotal;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin reports over stock lots: lots of a variant, near-expiry, valuation, margin and the lot/stock drift check. All lists are paged or bounded. */
@Service
public class LotReportService {
  /** Most rows of the expiry list and the drift check. */
  public static final int MAX_ROWS = 200;
  private static final Set<String> GROUPS = Set.of("product", "category", "day");

  /** Stock value by product or category with the grand total. */
  public record Valuation(ValuationTotal total, Page<ValuationRow> rows) {}

  /** Lots expiring within {@code days} days (expired ones included) and whether the list was cut at {@link #MAX_ROWS}. */
  public record Expiring(int days, LocalDate until, List<LotRow> lots, boolean truncated) {}

  /** Products whose lots differ from their stock row; empty when all is well. */
  public record LotCheck(List<Drift> drifts, boolean truncated) {}

  private final LotReportRepository repo;
  private final Clock clock;
  private RuntimeSettings settings;

  /** Creates the service. */
  public LotReportService(LotReportRepository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  /** Optional (plain unit tests run without it): supplies the default expiry window. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  private LocalDate today() { return LocalDate.now(clock.withZone(InvoiceNumbers.SHOP_ZONE)); }

  /** The lots of a variant, newest first. */
  @Transactional(readOnly = true)
  public Page<LotRow> lots(UUID variantId, boolean onlyOpen, int page, int size) {
    return repo.lotsOf(variantId, onlyOpen, Math.max(0, page), Page.clampSize(size));
  }

  /** Lots expiring within {@code days} days; {@code days} defaults to the setting inventory.expiryAlertDays. */
  @Transactional(readOnly = true)
  public Expiring expiring(Integer days) {
    int window = days == null ? (settings == null ? 30 : settings.getInt(Settings.INVENTORY_EXPIRY_ALERT_DAYS)) : days;
    if (window < 0 || window > 365) throw ApiException.validation("days", "0 to 365");
    LocalDate until = today().plusDays(window);
    List<LotRow> rows = repo.expiring(until, MAX_ROWS + 1);
    return new Expiring(window, until, rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows, rows.size() > MAX_ROWS);
  }

  /** Stock value at cost, by product or category ({@code groupBy}), with the total and how much has no known cost. */
  @Transactional(readOnly = true)
  public Valuation valuation(String groupBy, int page, int size) {
    String g = groupBy == null ? "product" : groupBy.toLowerCase(Locale.ROOT);
    if (!g.equals("product") && !g.equals("category")) throw ApiException.validation("groupBy", "product or category");
    return new Valuation(repo.valuationTotal(), repo.valuation(g.equals("category"), Math.max(0, page), Page.clampSize(size)));
  }

  /** Margin by product, category or day for a range of India dates (default: this month up to today). */
  @Transactional(readOnly = true)
  public Page<MarginRow> margin(String groupBy, LocalDate from, LocalDate to, int page, int size) {
    String g = groupBy == null ? "product" : groupBy.toLowerCase(Locale.ROOT);
    if (!GROUPS.contains(g)) throw ApiException.validation("groupBy", "product, category or day");
    LocalDate end = to == null ? today() : to;
    LocalDate start = from == null ? end.withDayOfMonth(1) : from;
    if (start.isAfter(end)) throw ApiException.validation("from", "must not be after to");
    if (start.plusDays(366).isBefore(end)) throw ApiException.validation("to", "at most one year after from");
    return repo.margin(g, start.atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant(), end.plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant(),
        Math.max(0, page), Page.clampSize(size));
  }

  /** Lists variants whose lots do not add up to their stock row (should be empty). */
  @Transactional(readOnly = true)
  public LotCheck lotCheck() {
    List<Drift> rows = repo.drift(MAX_ROWS + 1);
    return new LotCheck(rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows, rows.size() > MAX_ROWS);
  }

  /** Lots past their expiry date that still hold stock, for the daily job. */
  List<LotRow> expired(int limit) { return repo.expired(today(), limit); }

  /** Lots expiring within the window for the daily job. */
  List<LotRow> expiringWithin(int days, int limit) { return repo.expiring(today().plusDays(days), limit); }

  /** Today in shop time. */
  LocalDate shopToday() { return today(); }
}
