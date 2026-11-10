package com.vijay.mall.gst.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.gst.domain.GstMatcher;
import com.vijay.mall.gst.domain.GstRow;
import com.vijay.mall.gst.infra.GstRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The GST and HSN master: answers "which HSN and rate for this product name" from an in-memory index, so a read never touches the
 * database. The index ({@link GstMatcher}) is built once the application is up, rebuilt right after every admin change commits
 * ({@link GstAdminService}) and by a 60-second check that notices edits made on another server ({@link #reloadIfChanged}).
 * The whole index is swapped as one immutable object, so a request never sees half of a reload.
 */
@Service
public class GstService {
  private static final Logger log = LoggerFactory.getLogger(GstService.class);
  /** Server-side fill (API_CONTRACT section 6) uses a stricter bar than the 0.5 the suggest endpoint shows. */
  static final double FILL_MIN_SCORE = 0.6;
  /** Two candidates whose scores differ by no more than this are a tie; a {@code VERIFIED} one wins the tie. */
  static final double TIE = 0.01;
  private static final int DEFAULT_LIMIT = 5;
  private static final BigDecimal MAX_PRICE = BigDecimal.valueOf(10_000_000);

  /** Everything the read endpoints need, replaced as one object. */
  private record Snapshot(GstMatcher matcher, List<GstRow.Hsn> all, List<GstRow.Rate> rates, String fingerprint) {}

  /** One master row as the API returns it (API_CONTRACT sections 1 and 2); {@code matchedName} and {@code score} are null in a list row. */
  @Schema(name = "GstRowView")
  public record RowView(UUID rowId, String hsn, String chapter, String description, String tariffDescription, String productDescription,
                        String nameGu, String classificationConditions, BigDecimal gstPercent, BigDecimal igstRate, BigDecimal cgstRate,
                        BigDecimal sgstRate, BigDecimal cessRate, String taxType, String condition, String conditionLabel, BigDecimal priceMin,
                        BigDecimal priceMax, boolean acceptedForBilling, String matchedName, Double score, String verification,
                        String schedule, String notificationReference, String sourceReference, LocalDate effectiveFrom, String note,
                        boolean active, Instant updatedAt) {}

  /** {@code GET /gst/suggest}. */
  @Schema(name = "GstSuggestion")
  public record Suggestion(String query, RowView best, List<RowView> choices, List<RowView> alternatives) {}

  /** {@code GET /gst/hsn/{hsn}}. */
  @Schema(name = "GstHsnRows")
  public record HsnRows(String hsn, List<RowView> items) {}

  /** One entry of the rate card. */
  @Schema(name = "GstRateView")
  public record RateView(String code, BigDecimal gstPercent, BigDecimal cgstRate, BigDecimal sgstRate, BigDecimal igstRate, BigDecimal cessRate,
                         String taxType, boolean acceptedForBilling, String note) {}

  /** {@code GET /gst/rates}. */
  @Schema(name = "GstRateList")
  public record RateList(List<RateView> items) {}

  /**
   * What a product form, a purchase line or the purchase lookup gets when it left HSN or GST blank.
   *
   * @param hsn the HSN code
   * @param gstPercent the rate, or null when billing cannot store the master's rate yet (then fill the HSN only)
   * @param verification {@code VERIFIED} or {@code VERIFY_PRODUCT} (show "confirm with your CA")
   * @param rowId the master row used, for the audit line
   * @param score the match confidence, at least {@value #FILL_MIN_SCORE}
   */
  public record Defaults(String hsn, BigDecimal gstPercent, String verification, UUID rowId, double score) {}

  private final GstRepository repo;
  private final ReentrantLock reloadLock = new ReentrantLock();
  private volatile Snapshot snap;

  /** Creates the service; the index is built when the application is ready. */
  public GstService(GstRepository repo) { this.repo = repo; }

  /** Builds the index once the application is up. A failure is logged and retried by the 60-second check; the server still starts. */
  @EventListener(ApplicationReadyEvent.class)
  void load() {
    try {
      reload();
    } catch (RuntimeException e) {
      log.error("GST master could not be loaded, suggestions answer 422 until the next check: {}", e.toString());
    }
  }

  /**
   * Reloads the master when its tables changed since the last load (an admin edit made on another server). Runs every 60 seconds;
   * a failed check keeps the loaded master. Public so a test or an operator task can run it at once.
   */
  @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
  public void reloadIfChanged() {
    try {
      Snapshot s = snap;
      if (s == null || !s.fingerprint().equals(repo.fingerprint())) reload();
    } catch (RuntimeException e) {
      log.warn("GST master reload check failed, keeping the loaded master: {}", e.toString());
    }
  }

  /** Reads the tables and swaps in a new index. The fingerprint is taken first, so an edit during the read is caught by the next check. */
  void reload() {
    reloadLock.lock();
    try {
      String fingerprint = repo.fingerprint();
      List<GstRow.Hsn> all = repo.allHsn();
      GstMatcher matcher = new GstMatcher(all, repo.activeKeywords());
      snap = new Snapshot(matcher, List.copyOf(all), List.copyOf(repo.activeRates()), fingerprint);
      log.info("GST master loaded: {} HSN rows, {} keywords", matcher.hsnRowCount(), matcher.keywordCount());
    } finally {
      reloadLock.unlock();
    }
  }

  private Snapshot snapshot() {
    Snapshot s = snap;
    if (s == null) throw ApiException.unprocessable("GST_MASTER_LOADING", "The GST master is still loading, try again in a moment", Map.of());
    return s;
  }

  /**
   * {@code GET /gst/suggest}. Parameters are checked here so every caller gets the same 400.
   *
   * @param name the product name, 1 to 120 characters
   * @param category the category name, optional
   * @param price rupees, 0 to 10,000,000, optional
   * @param packed true packed and labelled, false loose, null decide from the name
   * @param limit 1 to 10, default 5
   * @return the best match, the other conditions of its HSN and other likely HSN codes
   */
  public Suggestion suggest(String name, String category, BigDecimal price, Boolean packed, Integer limit) {
    String n = name == null ? "" : name.strip();
    if (n.isEmpty() || n.length() > 120) throw ApiException.validation("name", "must be 1 to 120 characters");
    if (price != null && (price.signum() < 0 || price.compareTo(MAX_PRICE) > 0)) throw ApiException.validation("price", "must be 0 to 10000000");
    int lim = limit == null ? DEFAULT_LIMIT : limit;
    if (lim < 1 || lim > 10) throw ApiException.validation("limit", "must be 1 to 10");
    GstMatcher.Result r = snapshot().matcher().suggest(n, category, price, packed, lim);
    return new Suggestion(r.query(), r.best() == null ? null : GstViews.hit(r.best()),
        r.choices().stream().map(GstViews::hit).toList(), r.alternatives().stream().map(GstViews::hit).toList());
  }

  /**
   * {@code GET /gst/hsn}: code prefix or word search with filters and exact paging. A blank search lists every row, switched-off
   * rows included (so the review page can see them); a text search ranks active rows only.
   */
  public Page<RowView> list(String q, String verification, String taxType, int page, int size) {
    if (page < 0) throw ApiException.validation("page", "must be 0 or more");
    if (size < 1 || size > 50) throw ApiException.validation("size", "must be 1 to 50");
    boolean byVerification = verification != null && !verification.isBlank();
    boolean byTax = taxType != null && !taxType.isBlank();
    if (byVerification && !GstRowValidator.VERIFICATION.contains(verification)) {
      throw ApiException.validation("verification", "must be VERIFIED or VERIFY_PRODUCT");
    }
    if (byTax && !GstRowValidator.TAX_TYPES.contains(taxType)) throw ApiException.validation("taxType", "must be one of TAXABLE, NIL, EXEMPT, NON_GST");
    Snapshot s = snapshot();
    List<GstRow.Hsn> base = q != null && !q.isBlank() ? s.matcher().search(q, 0) : s.all();
    List<GstRow.Hsn> rows = new ArrayList<>();
    for (GstRow.Hsn h : base) {
      if (byVerification && !verification.equals(h.verification())) continue;
      if (byTax && !taxType.equals(h.taxType())) continue;
      rows.add(h);
    }
    int from = (int) Math.min((long) page * size, rows.size());
    List<RowView> items = rows.subList(from, Math.min(from + size, rows.size())).stream().map(GstViews::row).toList();
    return new Page<>(items, page, size, rows.size());
  }

  /** {@code GET /gst/hsn/{hsn}}: every row of the code (4, 6 or 8 digits), switched-off rows included; 404 {@code NOT_FOUND} for an unknown code. */
  public HsnRows byHsn(String hsn) {
    String code = hsn == null ? "" : hsn.strip();
    if (!GstRowValidator.HSN.matcher(code).matches()) throw ApiException.validation("hsn", "must be 4, 6 or 8 digits");
    List<RowView> items = snapshot().all().stream().filter(h -> h.hsn().equals(code)).map(GstViews::row).toList();
    if (items.isEmpty()) throw ApiException.notFound("HSN code");
    return new HsnRows(code, items);
  }

  /** {@code GET /gst/rates}: the fixed-percent rate card with whether billing can store each rate. */
  public RateList rates() {
    return new RateList(snapshot().rates().stream().map(GstViews::rate).toList());
  }

  /**
   * The values to put into a blank HSN or GST field (API_CONTRACT section 6): the best match for the name when it scores at least
   * {@value #FILL_MIN_SCORE}. Among candidates within {@value #TIE} of the best score a {@code VERIFIED} one is preferred.
   * {@code gstPercent} of the result is null when billing cannot store the master's rate yet (see {@link BillingRateRule}).
   * Empty when the master is not loaded, the name is blank or nothing scores high enough.
   *
   * @param productName the product's name
   * @param categoryName the category's name, optional
   * @param price the selling price in rupees, optional
   * @return the defaults, or empty
   */
  public Optional<Defaults> defaultsFor(String productName, String categoryName, BigDecimal price) {
    Snapshot s = snap;
    if (productName == null || productName.isBlank() || s == null) return Optional.empty();
    GstMatcher.Result r = s.matcher().suggest(productName.strip(), categoryName, price, null, 3);
    if (r.best() == null || r.best().score() < FILL_MIN_SCORE) return Optional.empty();
    GstMatcher.Hit pick = r.best();
    if (!"VERIFIED".equals(pick.verification())) {
      for (GstMatcher.Hit alt : r.alternatives()) {
        if ("VERIFIED".equals(alt.verification()) && r.best().score() - alt.score() <= TIE && alt.score() >= FILL_MIN_SCORE) {
          pick = alt;
          break;
        }
      }
    }
    GstRow.Hsn row = pick.row();
    BigDecimal rate = BillingRateRule.accepts(row.gstPercent()) ? GstViews.clean(row.gstPercent()) : null;
    return Optional.of(new Defaults(row.hsn(), rate, pick.verification(), row.id(), pick.score()));
  }
}
