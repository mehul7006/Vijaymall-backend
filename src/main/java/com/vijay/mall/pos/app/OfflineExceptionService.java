package com.vijay.mall.pos.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.metrics.Metrics;
import com.vijay.mall.pos.domain.OfflineFinding;
import com.vijay.mall.pos.infra.OfflineExceptionRepository;
import com.vijay.mall.pos.infra.OfflineExceptionRepository.Row;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The differences found while recording bills a till rang up offline (a changed price or GST rate, a discount, stock that ran
 * short, a product switched off). A difference never stops a bill from being recorded; it is kept here until a manager has
 * looked at it and says so. Each exception can be reviewed once.
 */
@Service
public class OfflineExceptionService {
  private static final Logger log = LoggerFactory.getLogger(OfflineExceptionService.class);
  private static final int MAX_NOTE = 500;

  private final OfflineExceptionRepository repo;
  private final AuditService audit;
  private final MeterRegistry metrics;
  private final Clock clock;

  public OfflineExceptionService(OfflineExceptionRepository repo, AuditService audit, MeterRegistry metrics, Clock clock) {
    this.repo = repo;
    this.audit = audit;
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Stores the findings of one bill (nothing when the list is empty), marks the bill for review and counts them by kind. */
  @Transactional
  public void record(UUID saleId, List<OfflineFinding> findings) {
    for (OfflineFinding f : findings) {
      repo.insert(saleId, f, clock.instant());
      Metrics.count(metrics, "mall.pos.offline.exceptions.recorded", "Differences found in offline counter bills, by kind", "kind",
          f.kind().name(), 1);
    }
  }

  /** The findings stored for one bill. */
  public List<OfflineFinding> forSale(UUID saleId) { return repo.forSale(saleId); }

  /** status OPEN (default) or REVIEWED; newest first. */
  public Page<Row> list(String status, int page, int size) {
    String s = status == null || status.isBlank() ? "OPEN" : status.strip().toUpperCase();
    if (!s.equals("OPEN") && !s.equals("REVIEWED")) throw ApiException.validation("status", "OPEN or REVIEWED");
    int p = Math.max(0, page);
    int n = Page.clampSize(size);
    boolean reviewed = s.equals("REVIEWED");
    return new Page<>(repo.page(reviewed, p, n), p, n, repo.count(reviewed));
  }

  /** Marks an exception as looked at, with an optional note. Only the first review counts; a second one is a conflict. */
  @Transactional
  public Row review(UUID id, String note, UUID reviewer) {
    String n = note == null || note.isBlank() ? null : note.strip();
    if (n != null && n.length() > MAX_NOTE) throw ApiException.validation("note", "at most " + MAX_NOTE + " characters");
    if (!repo.review(id, reviewer, n, clock.instant())) {
      repo.find(id).orElseThrow(() -> ApiException.notFound("Offline exception"));
      throw ApiException.conflict("ALREADY_REVIEWED", "This exception was already reviewed", Map.of());
    }
    Row row = repo.find(id).orElseThrow();
    audit.log(reviewer, "OFFLINE_EXCEPTION_REVIEWED", "pos_sale", row.saleId(), row.kind() + " " + (n == null ? "" : n));
    log.atInfo().setMessage("Offline exception reviewed").addKeyValue("event", "offline-exception-reviewed")
        .addKeyValue("kind", row.kind().name()).addKeyValue("saleId", row.saleId().toString()).log();
    return row;
  }

  /** Exceptions waiting for a manager. */
  public long openCount() { return repo.openCount(); }

  /** Exceptions that have waited longer than a day. */
  public long staleCount() { return repo.openOlderThan(clock.instant().minus(Duration.ofDays(1))); }
}
