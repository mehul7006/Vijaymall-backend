package com.vijay.mall.payment.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.infra.BankReportRepository;
import com.vijay.mall.payment.infra.BankReportRepository.BookRow;
import com.vijay.mall.payment.infra.BankReportRepository.FeeRow;
import com.vijay.mall.payment.infra.BankReportRepository.PayerRow;
import com.vijay.mall.payment.infra.BankReportRepository.SalePayerRow;
import com.vijay.mall.payment.infra.BankReportRepository.ReceiptRow;
import com.vijay.mall.payment.infra.BankReportRepository.SettlementItemRow;
import com.vijay.mall.payment.infra.BankReportRepository.SettlementRow;
import com.vijay.mall.payment.infra.BankReportRepository.UnsettledRow;
import com.vijay.mall.payment.infra.MoneyAccountRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The admin's bank and settlement reports: receipts by account, settlements, unsettled money, fees, an account's book, payer detail. */
@Service
public class BankReportService {
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  /** Longest date range one report reads, so a heavy query cannot be asked for by accident. */
  static final int MAX_DAYS = 400;
  /** A report without dates covers this many days up to today. */
  static final int DEFAULT_DAYS = 30;

  /** An unsettled line with when the money is expected and whether that day has passed. */
  public record Unsettled(UnsettledRow row, Instant expectedBy, boolean overdue) {}

  /** The book of one account: the balance before the range and the lines of the range. */
  public record Book(UUID moneyAccountId, String accountName, long openingBalancePaise, Page<BookRow> lines) {}

  private final BankReportRepository repo;
  private final MoneyAccountRepository accounts;
  private final RuntimeSettings settings;
  private final Clock clock;

  /** Creates the service. */
  public BankReportService(BankReportRepository repo, MoneyAccountRepository accounts, RuntimeSettings settings, Clock clock) {
    this.repo = repo;
    this.accounts = accounts;
    this.settings = settings;
    this.clock = clock;
  }

  /** A half-open range of instants. */
  private record Range(Instant from, Instant to) {}

  /** Both days are included, in India time; missing dates default to the last 30 days. */
  private Range range(LocalDate from, LocalDate to) {
    LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), INDIA);
    LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
    if (start.isAfter(end)) throw ApiException.validation("from", "must not be after to");
    if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw ApiException.validation("to", "at most " + MAX_DAYS + " days at a time");
    return new Range(start.atStartOfDay(INDIA).toInstant(), end.plusDays(1).atStartOfDay(INDIA).toInstant());
  }

  /** Money received and refunded per account and method. */
  public Page<ReceiptRow> receipts(LocalDate from, LocalDate to, int page, int size) {
    Range r = range(from, to);
    return repo.receipts(r.from(), r.to(), Math.max(0, page), Page.clampSize(size));
  }

  /** Every receipt from every vendor and channel in the range, newest first. */
  public Page<BankReportRepository.ReceiptLine> receiptLines(LocalDate from, LocalDate to, String provider, UUID account, String method, int page, int size) {
    Range r = range(from, to);
    String m = method == null || method.isBlank() ? null : method.strip().toUpperCase(Locale.ROOT);
    return repo.receiptLines(r.from(), r.to(), provider == null || provider.isBlank() ? null : provider.strip().toLowerCase(Locale.ROOT), account, m,
        Math.max(0, page), Page.clampSize(size));
  }

  /** Totals per vendor, account and method of the same receipts. */
  public List<BankReportRepository.ReceiptTotal> receiptTotals(LocalDate from, LocalDate to) {
    Range r = range(from, to);
    return repo.receiptTotals(r.from(), r.to());
  }

  /** Settlements paid out in the range. */
  public Page<SettlementRow> settlements(LocalDate from, LocalDate to, UUID account, String provider, String source, int page, int size) {
    Range r = range(from, to);
    String src = source == null || source.isBlank() ? null : source.strip().toUpperCase(Locale.ROOT);
    if (src != null && !List.of("GATEWAY_API", "CSV").contains(src)) throw ApiException.validation("source", "GATEWAY_API or CSV");
    return repo.settlements(r.from(), r.to(), account, provider == null || provider.isBlank() ? null : provider.strip().toLowerCase(Locale.ROOT), src,
        Math.max(0, page), Page.clampSize(size));
  }

  /** One settlement. */
  public SettlementRow settlement(UUID id) { return repo.settlement(id).orElseThrow(() -> ApiException.notFound("Settlement")); }

  /** The lines of a settlement. */
  public Page<SettlementItemRow> settlementItems(UUID id, boolean unmatchedOnly, int page, int size) {
    settlement(id);
    return repo.settlementItems(id, unmatchedOnly, Math.max(0, page), Page.clampSize(size));
  }

  /**
   * Money received and not yet settled, oldest first, each with the day it is expected (received + the settlement cycle) and whether that
   * day plus one day of grace has passed.
   */
  public Page<Unsettled> unsettled(boolean overdueOnly, int page, int size) {
    Instant now = clock.instant();
    int days = settings.getInt(Settings.SETTLEMENT_DAYS);
    Instant cutoff = now.minus(days + 1L, ChronoUnit.DAYS);
    Page<UnsettledRow> p = repo.unsettled(overdueOnly ? cutoff : null, Math.max(0, page), Page.clampSize(size));
    return p.withItems(p.items().stream().map(u -> new Unsettled(u, u.receivedAt().plus(days, ChronoUnit.DAYS), u.receivedAt().isBefore(cutoff))).toList());
  }

  /** Gateway fee and GST on the fee, per month and account. */
  public Page<FeeRow> fees(LocalDate from, LocalDate to, int page, int size) {
    Range r = range(from, to);
    return repo.fees(r.from(), r.to(), Math.max(0, page), Page.clampSize(size));
  }

  /** The book of one account. */
  public Book book(UUID account, LocalDate from, LocalDate to, int page, int size) {
    var acc = accounts.find(account).orElseThrow(() -> ApiException.notFound("Money account"));
    Range r = range(from, to);
    long opening = repo.openingBalance(account, r.from());
    return new Book(account, acc.name(), opening, repo.book(account, r.from(), r.to(), opening, Math.max(0, page), Page.clampSize(size)));
  }

  /** How the payment lines of a counter bill were made (admin only; masked). */
  public List<SalePayerRow> salePayers(UUID saleId) { return repo.payersOfSale(saleId); }

  /** How the order's online payments were made (admin only; never a full card number). */
  public List<PayerRow> payers(UUID orderId) { return repo.payersOfOrder(orderId); }
}
