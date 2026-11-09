package com.vijay.mall.payment.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.domain.SettlementProvider;
import com.vijay.mall.payment.domain.SettlementProvider.Detail;
import com.vijay.mall.payment.domain.SettlementProvider.Item;
import com.vijay.mall.payment.domain.SettlementProvider.ItemKind;
import com.vijay.mall.payment.domain.SettlementProvider.Listing;
import com.vijay.mall.payment.domain.SettlementProvider.PaymentDetail;
import com.vijay.mall.payment.domain.SettlementProvider.Summary;
import com.vijay.mall.payment.infra.MoneyAccountRepository;
import com.vijay.mall.payment.infra.SettlementRepository;
import com.vijay.mall.payment.infra.SettlementRepository.ExceptionRow;
import com.vijay.mall.payment.infra.SettlementRepository.Match;
import com.vijay.mall.payment.infra.SettlementRepository.NeedsDetail;
import com.vijay.mall.payment.infra.SettlementRepository.Unsettled;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pulls settlements from the payment providers (daily, and when a provider says one was processed), imports statement CSVs, matches
 * every line to our payments, refunds and counter lines, and raises what does not add up to the exception queue.
 */
@Service
public class SettlementService {
  private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

  /** How many days back the daily job looks. */
  public static final int LOOKBACK_DAYS = 7;
  /** Most list pages read from one provider in one run, so a runaway cursor cannot loop forever. */
  static final int MAX_PAGES = 20;
  /** How long one instance may hold the sync claim before another may take over. */
  static final int CLAIM_MINUTES = 15;
  /** Payments whose method detail is looked up per run. */
  static final int DETAIL_BATCH = 50;
  /** Overdue payments raised per run. */
  static final int OVERDUE_BATCH = 500;

  /** What one sync run did. */
  public record SyncResult(String provider, int settlementsSeen, int settlementsStored, int itemsStored, boolean skipped) {}

  /** What a CSV import did; {@code alreadyImported} is true when the same file was imported before (nothing changed). */
  public record ImportResult(UUID settlementId, boolean alreadyImported, int rows, int matched, int unmatched, long netPaise) {}

  /**
   * Which column holds what. Names are matched ignoring case; {@code fee}, {@code tax} and {@code description} may be blank.
   * @param dateFormat a java date pattern such as {@code dd/MM/yyyy}; blank reads ISO dates (2026-11-09)
   * @param amountUnit RUPEES (default, decimals allowed) or PAISE
   * @param delimiter one character, default a comma
   */
  public record CsvMapping(String date, String reference, String amount, String fee, String tax, String description, String dateFormat, String amountUnit,
                 String delimiter) {}

  private final SettlementRepository repo;
  private final SettlementProviderRegistry registry;
  private final MoneyAccountService accounts;
  private final MoneyAccountRepository accountRepo;
  private final RuntimeSettings settings;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;

  /** Creates the service. */
  public SettlementService(SettlementRepository repo, SettlementProviderRegistry registry, MoneyAccountService accounts,
                           MoneyAccountRepository accountRepo, RuntimeSettings settings, AuditService audit, Clock clock, TransactionTemplate tx) {
    this.repo = repo;
    this.registry = registry;
    this.accounts = accounts;
    this.accountRepo = accountRepo;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
    this.tx = tx;
  }

  // ---------- pull ----------
  /** Every night: pulls the last week of settlements from each set-up provider, then looks for payments that should have settled by now. */
  @Scheduled(cron = "0 30 4 * * *")
  public void nightly() {
    for (SettlementProvider p : registry.pullable()) {
      try {
        sync(p.provider(), LOOKBACK_DAYS);
      } catch (RuntimeException e) {
        log.warn("Settlement sync failed provider={} : {}", p.provider(), e.getMessage());
      }
    }
    checkOverdue();
  }

  /** Every 30 minutes: looks up how recent payments were paid (UPI id, card network, fee) for the ones the capture could not fill in. */
  @Scheduled(fixedDelayString = "PT30M", initialDelayString = "PT5M")
  public void fillPaymentDetails() {
    for (NeedsDetail n : repo.needingDetail(DETAIL_BATCH)) fillDetail(n.paymentId(), n.provider(), n.providerRef());
  }

  /**
   * Asks the provider how one payment was made and stores the answer (method, UPI id or card network and last four, bank, fee). Never throws:
   * a provider that is down or has no detail leaves the payment for the 30-minute job.
   */
  public void fillDetail(UUID paymentId, String provider, String providerRef) {
    SettlementProvider p = registry.find(provider);
    if (p == null || !p.configured()) return;
    try {
      Optional<PaymentDetail> d = p.fetchPaymentDetail(providerRef);
      if (d.isPresent()) repo.savePaymentDetail(paymentId, d.get(), clock.instant());
      else repo.markDetailFetched(paymentId, clock.instant());
    } catch (RuntimeException e) {
      log.warn("Payment detail lookup failed payment={} : {}", paymentId, e.getMessage());
    }
  }

  /**
   * Pulls the settlements of the last {@code days} days. Safe to call from several instances and many times: a claim row lets only one
   * instance run at a time (the others return {@code skipped}), and every settlement and item is stored once.
   *
   * @throws ApiException 404 for an unknown provider, 503 {@code SETTLEMENT_NOT_CONFIGURED} when its keys are missing
   */
  public SyncResult sync(String providerName, int days) {
    SettlementProvider p = registry.byProvider(providerName);
    if (!p.configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SETTLEMENT_NOT_CONFIGURED", "This provider is not set up yet");
    Instant now = clock.instant();
    if (!repo.claimSync(p.provider(), now, now.plus(CLAIM_MINUTES, ChronoUnit.MINUTES))) {
      return new SyncResult(p.provider(), 0, 0, 0, true);
    }
    String error = null;
    int seen = 0;
    int stored = 0;
    int items = 0;
    try {
      String cursor = null;
      for (int page = 0; page < MAX_PAGES; page++) {
        Listing l = p.listSettlements(now.minus(Math.max(1, days), ChronoUnit.DAYS), now, cursor);
        for (Summary s : l.items()) {
          seen++;
          int n = storeIfNew(p, s);
          if (n >= 0) {
            stored++;
            items += n;
          }
        }
        cursor = l.nextCursor();
        if (cursor == null) break;
      }
    } catch (RuntimeException e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
      throw e;
    } finally {
      repo.finishSync(p.provider(), clock.instant(), error == null ? null : error.substring(0, Math.min(500, error.length())));
    }
    log.info("event=settlement-sync provider={} seen={} stored={} items={}", p.provider(), seen, stored, items);
    return new SyncResult(p.provider(), seen, stored, items, false);
  }

  /** A provider told us (webhook) that one settlement was paid out: pull just that one. Does nothing when it is already stored in full. */
  public void syncOne(String providerName, String settlementId) {
    SettlementProvider p = registry.byProvider(providerName);
    if (!p.configured()) return;
    try {
      Optional<Detail> d = p.settlementDetail(settlementId);
      if (d.isPresent() && !alreadyComplete(p.provider(), d.get().summary())) store(p.provider(), d.get(), "GATEWAY_API", accounts.gatewayAccountFor(p.provider()).orElse(null));
    } catch (RuntimeException e) {
      log.warn("Settlement webhook sync failed provider={} settlement={} : {}", p.provider(), settlementId, e.getMessage());
    }
  }

  private boolean alreadyComplete(String provider, Summary s) {
    return repo.find(provider, s.id()).filter(old -> old.status().equals(s.status()) && repo.itemCount(old.id()) > 0).isPresent();
  }

  /** Stores one listed settlement unless it is already complete; returns the number of new items, or -1 when it was skipped. */
  private int storeIfNew(SettlementProvider p, Summary s) {
    if (alreadyComplete(p.provider(), s)) return -1;
    Detail d = p.settlementDetail(s.id()).orElse(new Detail(s, List.of()));
    return store(p.provider(), d, "GATEWAY_API", accounts.gatewayAccountFor(p.provider()).orElse(null));
  }

  // ---------- store and match ----------
  /** Saves a settlement and its items in one transaction, matching each item; returns how many items were new. */
  private int store(String provider, Detail d, String source, UUID accountId) {
    return tx.execute(status -> {
      Instant now = clock.instant();
      Summary s = d.summary();
      UUID id = repo.upsert(new SettlementRepository.Settlement(UUID.randomUUID(), provider, s.id(), s.utr(), s.grossPaise(), s.feePaise(), s.taxPaise(),
          s.netPaise(), s.status(), s.settledAt(), accountId, source), now);
      int added = 0;
      for (Item i : d.items()) {
        if (storeItem(provider, id, s, i, now)) added++;
      }
      return added;
    });
  }

  private boolean storeItem(String provider, UUID settlementId, Summary s, Item i, Instant now) {
    Matched m = match(i);
    Optional<UUID> itemId = repo.insertItem(new SettlementRepository.Item(UUID.randomUUID(), settlementId, i.kind().name(), i.entityId(), m.paymentId(),
        m.refundId(), m.posPaymentId(), i.grossPaise(), i.feePaise(), i.taxPaise(), i.netPaise(), i.method(), i.settledAt(), i.utr(), i.reference(),
        m.ok()));
    if (itemId.isEmpty()) return false;
    if (m.ok() && m.paymentId() != null) {
      repo.markPaymentSettled(m.paymentId(), s.id(), i.utr(), i.settledAt(), i.feePaise(), i.taxPaise(), i.netPaise(), i.method(), now);
      repo.closeOverdue(m.paymentId(), now);
    }
    if (!m.ok()) {
      String kind = m.mismatch() ? "AMOUNT_MISMATCH" : "UNMATCHED_ITEM";
      String ref = i.kind() == ItemKind.REFUND && i.refundRef() != null ? i.refundRef() : i.paymentRef() != null ? i.paymentRef()
          : i.reference() != null ? i.reference() : i.entityId();
      repo.raise(kind, "item:" + itemId.get(), settlementId, itemId.get(), m.paymentId(), ref, m.expectedPaise(), i.grossPaise(), now);
      log.warn("event=settlement-exception kind={} provider={} settlement={} ref={}", kind, provider, s.id(), ref);
    }
    return true;
  }

  /** How an item matched: ok, or not (a mismatch carries the expected amount). Ids are set for whatever was found. */
  private record Matched(boolean ok, boolean mismatch, UUID paymentId, UUID refundId, UUID posPaymentId, Long expectedPaise) {}

  private Matched match(Item i) {
    return switch (i.kind()) {
      case ADJUSTMENT, FEE -> new Matched(true, false, null, null, null, null);
      case REFUND -> {
        Optional<Match> r = i.refundRef() == null ? Optional.empty() : repo.refundByProviderRef(i.refundRef());
        yield r.map(x -> x.amountPaise() == i.grossPaise() ? new Matched(true, false, null, x.id(), null, null)
            : new Matched(false, true, null, x.id(), null, x.amountPaise())).orElse(new Matched(false, false, null, null, null, null));
      }
      case PAYMENT -> {
        String online = i.paymentRef() != null ? i.paymentRef() : i.reference();
        Optional<Match> pay = online == null || online.isBlank() ? Optional.empty() : repo.paymentByProviderRef(online);
        if (pay.isPresent()) {
          Match x = pay.get();
          yield x.amountPaise() == i.grossPaise() ? new Matched(true, false, x.id(), null, null, null) : new Matched(false, true, x.id(), null, null, x.amountPaise());
        }
        Optional<Match> pos = i.reference() == null || i.reference().isBlank() ? Optional.empty() : repo.freePosPaymentByReference(i.reference(), i.grossPaise());
        if (pos.isPresent()) {
          Match x = pos.get();
          yield x.amountPaise() == i.grossPaise() ? new Matched(true, false, null, null, x.id(), null) : new Matched(false, true, null, null, x.id(), x.amountPaise());
        }
        yield new Matched(false, false, null, null, null, null);
      }
    };
  }

  // ---------- overdue ----------
  /**
   * Raises an "overdue" exception for every captured online payment still unsettled after the settlement cycle
   * ({@code payment.settlementDays}) plus one day of grace. Returns how many new ones were raised.
   */
  public int checkOverdue() {
    Instant now = clock.instant();
    Instant cutoff = now.minus(settings.getInt(Settings.SETTLEMENT_DAYS) + 1L, ChronoUnit.DAYS);
    int raised = 0;
    for (Unsettled u : repo.overdue(cutoff, OVERDUE_BATCH)) {
      if (repo.raise("OVERDUE_UNSETTLED", "overdue:" + u.paymentId(), null, null, u.paymentId(), u.paymentId().toString(), u.amountPaise(), null, now)) raised++;
    }
    if (raised > 0) log.warn("event=settlement-overdue raised={}", raised);
    return raised;
  }

  // ---------- CSV ----------
  /**
   * Imports an acquirer or bank statement. Rows become one settlement with items; each is matched by reference and amount to an online
   * payment or a counter UPI/card line, and what does not match goes to the exception queue. The same file imported twice changes nothing.
   *
   * @param provider a free code for the source, default {@code manual}
   * @param accountId the money account the statement belongs to (required)
   * @param utr optional bank reference of the whole payout
   */
  public ImportResult importCsv(UUID actor, byte[] bytes, String provider, UUID accountId, String utr, CsvMapping mapping) {
    if (accountId == null) throw ApiException.validation("moneyAccountId", "required");
    accountRepo.find(accountId).orElseThrow(() -> ApiException.validation("moneyAccountId", "no such money account"));
    String code = provider == null || provider.isBlank() ? "manual" : provider.strip().toLowerCase(Locale.ROOT);
    if (code.length() > 40) throw ApiException.validation("provider", "at most 40 characters");
    List<SettlementCsv.Row> rows = SettlementCsv.parse(bytes, mapping);
    String sid = "csv-" + digest(bytes, accountId);
    Optional<SettlementRepository.Settlement> old = repo.find(code, sid);
    if (old.isPresent()) {
      return new ImportResult(old.get().id(), true, rows.size(), 0, 0, old.get().netPaise());
    }
    List<Item> items = new ArrayList<>();
    for (SettlementCsv.Row r : rows) {
      boolean credit = r.amountPaise() >= 0;
      long gross = Math.abs(r.amountPaise());
      items.add(new Item("row-" + r.line(), credit ? ItemKind.PAYMENT : ItemKind.ADJUSTMENT, null, null, gross, r.feePaise(), r.taxPaise(),
          credit ? gross - r.feePaise() - r.taxPaise() : -gross, null, r.at(), utr, r.reference().isBlank() ? null : r.reference()));
    }
    long gross = items.stream().filter(i -> i.kind() == ItemKind.PAYMENT).mapToLong(Item::grossPaise).sum();
    long fee = items.stream().mapToLong(Item::feePaise).sum();
    long tax = items.stream().mapToLong(Item::taxPaise).sum();
    long net = items.stream().mapToLong(Item::netPaise).sum();
    Instant last = items.stream().map(Item::settledAt).max(Comparator.naturalOrder()).orElse(clock.instant());
    Detail d = new Detail(new Summary(sid, utr == null || utr.isBlank() ? null : utr.strip(), gross, fee, tax, net, "IMPORTED", last), items);
    store(code, d, "CSV", accountId);
    SettlementRepository.Settlement saved = repo.find(code, sid).orElseThrow();
    List<SettlementRepository.Item> stored = repo.items(saved.id());
    int matched = (int) stored.stream().filter(SettlementRepository.Item::matched).count();
    audit.log(actor, "SETTLEMENT_IMPORT", "settlement", saved.id(), code + " rows=" + rows.size() + " matched=" + matched);
    return new ImportResult(saved.id(), false, rows.size(), matched, stored.size() - matched, net);
  }

  private static String digest(byte[] bytes, UUID accountId) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(accountId.toString().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest(bytes)).substring(0, 24);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------- exceptions ----------
  /** The problem queue, newest first. */
  public Page<ExceptionRow> exceptions(String status, String kind, int page, int size) {
    String s = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
    if (s != null && !List.of("OPEN", "RESOLVED").contains(s)) throw ApiException.validation("status", "OPEN or RESOLVED");
    String k = kind == null || kind.isBlank() ? null : kind.strip().toUpperCase(Locale.ROOT);
    if (k != null && !List.of("OVERDUE_UNSETTLED", "UNMATCHED_ITEM", "AMOUNT_MISMATCH").contains(k)) {
      throw ApiException.validation("kind", "OVERDUE_UNSETTLED, UNMATCHED_ITEM or AMOUNT_MISMATCH");
    }
    return repo.exceptions(s, k, Math.max(0, page), Page.clampSize(size));
  }

  /**
   * Closes an open problem with the admin's note; resolving a resolved one again returns it unchanged.
   * @throws ApiException 400 when the note is blank or longer than 300 characters; 404 for an unknown id
   */
  public ExceptionRow resolve(UUID actor, UUID id, String note) {
    String n = note == null ? "" : note.strip();
    if (n.isEmpty() || n.length() > 300) throw ApiException.validation("note", "1 to 300 characters");
    ExceptionRow e = repo.findException(id).orElseThrow(() -> ApiException.notFound("Exception"));
    if (repo.resolve(id, n, actor, clock.instant())) audit.log(actor, "SETTLEMENT_EXCEPTION_RESOLVE", "settlement_exception", id, e.kind());
    return repo.findException(id).orElseThrow();
  }
}
