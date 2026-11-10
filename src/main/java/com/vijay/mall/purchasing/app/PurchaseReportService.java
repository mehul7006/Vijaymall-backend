package com.vijay.mall.purchasing.app;

import com.vijay.mall.common.web.GstRateJson;
import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.purchasing.domain.PurchaseMath;
import com.vijay.mall.purchasing.infra.PurchaseReportRepository;
import com.vijay.mall.purchasing.infra.PurchaseReportRepository.AgeingRow;
import com.vijay.mall.purchasing.infra.PurchaseReportRepository.DiscountRow;
import com.vijay.mall.purchasing.infra.PurchaseReportRepository.GstRow;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;
import org.springframework.stereotype.Service;

/** Purchase reports for the admin: register, input GST, discounts and payables ageing. Dates are India dates; ranges are at most a year. */
@Service
public class PurchaseReportService {
  /** One purchase line in the register (money in paise, the landed unit cost has 4 decimals). */
  @Schema(name = "PurchaseRegisterRow")
  public record RegisterView(UUID purchaseId, String invoiceNo, LocalDate invoiceDate, UUID supplierId, String supplierName, String gstMode,
                             UUID productId, String productName, int qty, int freeQty, BigDecimal unitCostPaise, long discountPaise, long taxablePaise,
                             @GstRateJson BigDecimal gstPercent, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise, long lineTotalPaise,
                             BigDecimal landedUnitCostPaise) {}

  /** Input tax of one GST mode and rate, net of debit notes. Creditable tax is claimed back (REGULAR); the rest is part of the cost. */
  @Schema(name = "PurchaseGstLine")
  public record GstLine(String gstMode, @GstRateJson BigDecimal gstPercent, long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise, long cessPaise,
                        long creditablePaise, long nonCreditablePaise) {}

  /** The input GST report: one line per mode and rate, and the sums. */
  @Schema(name = "PurchaseInputGstReport")
  public record InputGst(LocalDate from, LocalDate to, List<GstLine> lines, long taxablePaise, long cgstPaise, long sgstPaise, long igstPaise,
                         long cessPaise, long creditablePaise, long nonCreditablePaise) {}

  /** The discount report: totals and one line per supplier. */
  @Schema(name = "PurchaseDiscountsReport")
  public record Discounts(LocalDate from, LocalDate to, long grossPaise, long lineDiscountPaise, long docDiscountPaise, List<DiscountRow> bySupplier) {}

  /** The payables ageing: one line per supplier owed money by the age of the invoice, and the sums. */
  @Schema(name = "PurchaseAgeingReport")
  public record Ageing(LocalDate asOf, long days0to30Paise, long days31to60Paise, long days61PlusPaise, long creditPaise, long totalDuePaise,
                       List<AgeingRow> bySupplier) {}

  private static final int MAX_DAYS = 366;

  private final PurchaseReportRepository repo;
  private final CatalogService catalog;
  private final Clock clock;

  /** Creates the service. */
  public PurchaseReportService(PurchaseReportRepository repo, CatalogService catalog, Clock clock) {
    this.repo = repo;
    this.catalog = catalog;
    this.clock = clock;
  }

  private LocalDate today() { return LocalDate.now(clock.withZone(InvoiceNumbers.SHOP_ZONE)); }

  /** The range to report on: {@code to} defaults to today and {@code from} to the first day of that month; at most 366 days. */
  private LocalDate[] range(LocalDate from, LocalDate to) {
    LocalDate end = to == null ? today() : to;
    LocalDate start = from == null ? end.withDayOfMonth(1) : from;
    if (start.isAfter(end)) throw ApiException.validation("from", "must not be after to");
    if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw ApiException.validation("from", "at most " + MAX_DAYS + " days");
    return new LocalDate[] {start, end};
  }

  /** The purchase lines in the range, newest first; filters are optional. */
  public Page<RegisterView> register(LocalDate from, LocalDate to, UUID supplierId, UUID productId, BigDecimal gstRate, int page, int size) {
    LocalDate[] r = range(from, to);
    Page<PurchaseReportRepository.RegisterRow> p = repo.register(r[0], r[1], supplierId, productId, gstRate, Math.max(0, page), Page.clampSize(size));
    Map<UUID, String> names = catalog.names(p.items().stream().map(PurchaseReportRepository.RegisterRow::productId).distinct().toList());
    return p.withItems(p.items().stream().map(x -> new RegisterView(x.purchaseId(), x.invoiceNo(), x.invoiceDate(), x.supplierId(), x.supplierName(),
        x.gstMode(), x.productId(), names.getOrDefault(x.productId(), "?"), x.qty(), x.freeQty(), PurchaseMath.plain(x.unitCostPaise()), x.discountPaise(), x.taxablePaise(),
        x.gstPercent(), x.cgstPaise(), x.sgstPaise(), x.igstPaise(), x.cessPaise(), x.lineTotalPaise(), x.landedUnitCost().setScale(4))).toList());
  }

  /** Input GST by mode and rate for the range: purchases and freight by invoice date, minus debit notes by the day they were made. */
  public InputGst inputGst(LocalDate from, LocalDate to) {
    LocalDate[] r = range(from, to);
    List<GstRow> rows = repo.inputGst(r[0], r[1], r[0].atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant(),
        r[1].plusDays(1).atStartOfDay(InvoiceNumbers.SHOP_ZONE).toInstant());
    List<GstLine> lines = rows.stream().map(x -> {
      long tax = x.cgstPaise() + x.sgstPaise() + x.igstPaise() + x.cessPaise();
      boolean credit = "REGULAR".equals(x.gstMode());
      return new GstLine(x.gstMode(), x.gstPercent(), x.taxablePaise(), x.cgstPaise(), x.sgstPaise(), x.igstPaise(), x.cessPaise(), credit ? tax : 0,
          credit ? 0 : tax);
    }).toList();
    return new InputGst(r[0], r[1], lines, sum(lines, GstLine::taxablePaise), sum(lines, GstLine::cgstPaise), sum(lines, GstLine::sgstPaise),
        sum(lines, GstLine::igstPaise), sum(lines, GstLine::cessPaise), sum(lines, GstLine::creditablePaise), sum(lines, GstLine::nonCreditablePaise));
  }

  /** Discounts given by suppliers in the range, by supplier. */
  public Discounts discounts(LocalDate from, LocalDate to) {
    LocalDate[] r = range(from, to);
    List<DiscountRow> rows = repo.discounts(r[0], r[1]);
    return new Discounts(r[0], r[1], sum(rows, DiscountRow::grossPaise), sum(rows, DiscountRow::lineDiscountPaise), sum(rows, DiscountRow::docDiscountPaise), rows);
  }

  /** What is owed to each supplier as of a day (default today), by invoice age. */
  public Ageing ageing(LocalDate asOf) {
    LocalDate day = asOf == null ? today() : asOf;
    List<AgeingRow> rows = repo.ageing(day);
    return new Ageing(day, sum(rows, AgeingRow::days0to30Paise), sum(rows, AgeingRow::days31to60Paise), sum(rows, AgeingRow::days61PlusPaise),
        sum(rows, AgeingRow::creditPaise), sum(rows, AgeingRow::totalDuePaise), rows);
  }

  private static <T> long sum(List<T> rows, ToLongFunction<T> f) {
    long s = 0;
    for (T r : rows) s += f.applyAsLong(r);
    return s;
  }
}
