package com.vijay.mall.flash.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.flash.domain.FlashReportMath;
import com.vijay.mall.flash.domain.FlashStatus;
import com.vijay.mall.flash.infra.FlashRepository;
import com.vijay.mall.flash.infra.FlashRepository.ItemTotals;
import com.vijay.mall.flash.infra.FlashRepository.Sale;
import com.vijay.mall.flash.infra.FlashRepository.Totals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flash sale numbers. They come from the consumption table, so units given back (cancelled orders) are not counted. Hours and
 * dates are shop time (Asia/Kolkata). The counter reports are not touched: a flash sale line is an ordinary bill line.
 */
@Service
public class FlashReportService {
  /** Time zone of the shop, used for report days and hours. */
  static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
  /** Longest period a range report may cover, in days. */
  static final int MAX_DAYS = 366;
  /** How many best-selling items the range report lists. */
  static final int BEST_ITEMS = 10;

  /**
   * One product's results in a sale or in the best-items list. {@code sellThroughPercent} is units sold as a share of the cap, null when the item has no cap.
   */
  public record ItemLine(UUID productId, String nameEn, long units, long revenuePaise, long discountPaise, Integer maxQty,
                         Double sellThroughPercent) {}

  /** Units, revenue and discount given on one channel ({@code ONLINE} or {@code POS}). */
  public record ChannelLine(String channel, long units, long revenuePaise, long discountPaise) {}

  /** Units and revenue in one shop-time hour such as {@code 2026-11-08 14:00}. */
  public record HourLine(String hour, long units, long revenuePaise) {}

  /** orders = online orders that used the sale; bills = counter bills that did. avgDiscountPercent = discount / list value. */
  public record SaleReport(UUID saleId, String name, String status, Instant startsAt, Instant endsAt, long unitsSold, long orders, long bills,
                           long revenuePaise, long discountPaise, double avgDiscountPercent, long customers, Double sellThroughPercent,
                           List<ItemLine> byItem, List<ChannelLine> byChannel, List<HourLine> byHour) {}

  /** One sale's totals inside a range report. */
  public record SaleLine(UUID saleId, String name, String status, Instant startsAt, Instant endsAt, long unitsSold, long orders, long bills,
                         long revenuePaise, long discountPaise, double avgDiscountPercent, long customers) {}

  /** Totals over all sales that started in a date range, with one line per sale and the best items. Money is paise. */
  public record RangeReport(LocalDate from, LocalDate to, long sales, long unitsSold, long orders, long bills, long revenuePaise,
                            long discountPaise, double avgDiscountPercent, long customers, List<SaleLine> bySale, List<ItemLine> bestItems) {}

  private final FlashRepository repo;
  private final CatalogService catalog;
  private final Clock clock;

  /** Creates the service. */
  public FlashReportService(FlashRepository repo, CatalogService catalog, Clock clock) {
    this.repo = repo;
    this.catalog = catalog;
    this.clock = clock;
  }

  /**
   * Full report of one sale. Units given back by cancelled orders are not counted.
   * @throws ApiException {@code NOT_FOUND} when the sale does not exist
   */
  @Transactional(readOnly = true)
  public SaleReport report(UUID saleId) {
    Sale s = repo.findSale(saleId).orElseThrow(() -> ApiException.notFound("Flash sale"));
    Totals t = repo.totals(saleId);
    List<ItemTotals> items = repo.itemTotals(saleId);
    Map<UUID, String> names = catalog.names(items.stream().map(ItemTotals::productId).toList());
    long capped = items.stream().filter(i -> i.maxQty() != null).mapToLong(ItemTotals::units).sum();
    long caps = items.stream().filter(i -> i.maxQty() != null).mapToLong(i -> i.maxQty()).sum();
    Map<String, FlashRepository.ChannelTotals> byChannel = repo.channelTotals(saleId).stream()
        .collect(Collectors.toMap(FlashRepository.ChannelTotals::channel, c -> c));
    List<ChannelLine> channels = List.of("ONLINE", "POS").stream().map(c -> {
      var ct = byChannel.get(c);
      return ct == null ? new ChannelLine(c, 0, 0, 0) : new ChannelLine(c, ct.units(), ct.revenuePaise(), ct.discountPaise());
    }).toList();
    return new SaleReport(s.id(), s.name(), FlashStatus.of(s.startsAt(), s.endsAt(), s.cancelledAt(), clock.instant()).name(), s.startsAt(),
        s.endsAt(), t.units(), t.orders(), t.bills(), t.revenuePaise(), t.discountPaise(),
        FlashReportMath.averageDiscountPercent(t.revenuePaise(), t.discountPaise()), t.customers(), FlashReportMath.sellThroughPercent(capped, caps),
        items.stream().map(i -> new ItemLine(i.productId(), names.getOrDefault(i.productId(), "?"), i.units(), i.revenuePaise(), i.discountPaise(),
            i.maxQty(), i.maxQty() == null ? null : FlashReportMath.sellThroughPercent(i.units(), i.maxQty()))).toList(),
        channels, repo.hourTotals(saleId).stream().map(h -> new HourLine(h.hour(), h.units(), h.revenuePaise())).toList());
  }

  /** Sales that start from {@code from} to {@code to} (both days included, shop time); default is the last 30 days. */
  @Transactional(readOnly = true)
  public RangeReport range(LocalDate from, LocalDate to) {
    LocalDate today = clock.instant().atZone(SHOP_ZONE).toLocalDate();
    LocalDate end = to == null ? today : to;
    LocalDate start = from == null ? end.minusDays(29) : from;
    if (end.isBefore(start)) throw ApiException.validation("to", "must not be before from");
    if (start.plusDays(MAX_DAYS).isBefore(end)) throw ApiException.validation("to", "a report covers at most " + MAX_DAYS + " days");
    List<Sale> sales = repo.startingBetween(start.atStartOfDay(SHOP_ZONE).toInstant(), end.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant());
    List<UUID> ids = sales.stream().map(Sale::id).toList();
    Map<UUID, Totals> totals = repo.totalsBySale(ids);
    Instant now = clock.instant();
    Totals none = new Totals(0, 0, 0, 0, 0, 0);
    List<SaleLine> lines = sales.stream().map(s -> {
      Totals t = totals.getOrDefault(s.id(), none);
      return new SaleLine(s.id(), s.name(), FlashStatus.of(s.startsAt(), s.endsAt(), s.cancelledAt(), now).name(), s.startsAt(), s.endsAt(),
          t.units(), t.orders(), t.bills(), t.revenuePaise(), t.discountPaise(),
          FlashReportMath.averageDiscountPercent(t.revenuePaise(), t.discountPaise()), t.customers());
    }).toList();
    long revenue = lines.stream().mapToLong(SaleLine::revenuePaise).sum();
    long discount = lines.stream().mapToLong(SaleLine::discountPaise).sum();
    var best = repo.bestItems(ids, BEST_ITEMS);
    Map<UUID, String> names = catalog.names(best.stream().map(FlashRepository.BestItem::productId).toList());
    return new RangeReport(start, end, lines.size(), lines.stream().mapToLong(SaleLine::unitsSold).sum(),
        lines.stream().mapToLong(SaleLine::orders).sum(), lines.stream().mapToLong(SaleLine::bills).sum(), revenue, discount,
        FlashReportMath.averageDiscountPercent(revenue, discount), repo.customersAcross(ids), lines,
        best.stream().map(b -> new ItemLine(b.productId(), names.getOrDefault(b.productId(), "?"), b.units(), b.revenuePaise(), b.discountPaise(),
            null, null)).toList());
  }
}
