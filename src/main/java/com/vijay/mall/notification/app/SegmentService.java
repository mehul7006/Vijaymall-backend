package com.vijay.mall.notification.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.notification.domain.ConditionType;
import com.vijay.mall.notification.domain.Rule;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.notification.domain.SegmentValidator;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import com.vijay.mall.notification.infra.SegmentRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved customer segments: create, edit, archive, list, and preview who a set of conditions would reach. */
@Service
public class SegmentService {
  /** The preview counts exactly up to this many people. */
  static final int PREVIEW_CAP = 10_000;
  static final int SAMPLE = 5;
  static final int MAX_NAME = 60;
  static final int MAX_DESCRIPTION = 300;

  /** A saved segment as the API shows it. */
  @Schema(name = "SegmentView")
  public record View(UUID id, String name, String description, String match, List<Rule> rules, UUID createdBy, Instant createdAt, Instant updatedAt) {}

  /** One matching customer in a preview, with the mobile number masked. */
  public record SampleView(UUID id, String name, String mobile) {}

  /**
   * Who a segment would reach.
   * @param recipients matching customers, at most 10,000
   * @param totalExact false when more than 10,000 match (recipients then shows the cap)
   * @param withDevices how many of those {@code recipients} have a push device (never more than recipients)
   * @param sample up to five matching customers
   */
  public record PreviewResult(long recipients, boolean totalExact, long withDevices, List<SampleView> sample) {}

  /** The conditions a broadcast will use, and the saved segment they came from if any. */
  public record Resolved(UUID segmentId, Segment segment) {}

  private final SegmentRepository store;
  private final SegmentQueryRepository query;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service (wired by Spring). */
  public SegmentService(SegmentRepository store, SegmentQueryRepository query, AuditService audit, Clock clock) {
    this.store = store;
    this.query = query;
    this.audit = audit;
    this.clock = clock;
  }

  private static View view(SegmentRepository.Row r) {
    return new View(r.id(), r.name(), r.description(), r.match(), r.rules(), r.createdBy(), r.createdAt(), r.updatedAt());
  }

  private static String name(String raw) {
    String n = raw == null ? "" : raw.strip();
    if (n.isEmpty() || n.length() > MAX_NAME) throw ApiException.validation("name", "must be 1 to " + MAX_NAME + " characters");
    return n;
  }

  private static String description(String raw) {
    String d = raw == null ? "" : raw.strip();
    if (d.length() > MAX_DESCRIPTION) throw ApiException.validation("description", "must be at most " + MAX_DESCRIPTION + " characters");
    return d;
  }

  /** Saves a new segment. Fails with 400 for bad conditions and 409 {@code SEGMENT_NAME_TAKEN} for a name already in use. */
  @Transactional
  public View create(UUID actor, String name, String description, Segment segment) {
    String n = name(name);
    String d = description(description);
    Segment clean = SegmentValidator.validate(segment);
    if (store.nameTaken(n, new UUID(0, 0))) throw taken();
    UUID id = UUID.randomUUID();
    store.insert(id, n, d, clean, actor, clock.instant());
    audit.log(actor, "SEGMENT_CREATE", "notification_segment", id, n + " (" + clean.rules().size() + " conditions)");
    return get(id);
  }

  /** Replaces a segment. Past broadcasts keep the snapshot they were sent with. */
  @Transactional
  public View update(UUID actor, UUID id, String name, String description, Segment segment) {
    String n = name(name);
    String d = description(description);
    Segment clean = SegmentValidator.validate(segment);
    get(id);
    if (store.nameTaken(n, id)) throw taken();
    store.update(id, n, d, clean, clock.instant());
    audit.log(actor, "SEGMENT_UPDATE", "notification_segment", id, n + " (" + clean.rules().size() + " conditions)");
    return get(id);
  }

  /** Archives a segment (it disappears from the list; past broadcasts are unaffected). */
  @Transactional
  public void archive(UUID actor, UUID id) {
    View v = get(id);
    store.archive(id, clock.instant());
    audit.log(actor, "SEGMENT_DELETE", "notification_segment", id, v.name());
  }

  /** One live segment; 404 when unknown or archived. */
  public View get(UUID id) { return view(store.find(id).orElseThrow(() -> ApiException.notFound("Segment"))); }

  /** Live segments, newest first. */
  public Page<View> list(int page, int size) {
    int s = Page.clampSize(size);
    int p = Math.max(0, page);
    Page<SegmentRepository.Row> rows = store.page(p, s);
    return new Page<>(rows.items().stream().map(SegmentService::view).toList(), p, s, rows.total());
  }

  /**
   * The conditions to use: a saved segment (by id) or an inline set, exactly one of them.
   *
   * @throws ApiException 400 when both or neither are given or the conditions are invalid, 404 for an unknown saved segment
   */
  public Resolved resolve(UUID segmentId, Segment inline) {
    if ((segmentId == null) == (inline == null)) {
      throw ApiException.validation("segment", "give either segmentId or segment (match and rules), not both");
    }
    if (segmentId != null) return new Resolved(segmentId, store.find(segmentId).orElseThrow(() -> ApiException.notFound("Segment")).segment());
    return new Resolved(null, SegmentValidator.validate(inline));
  }

  /**
   * Shows who the conditions would reach: exact count up to 10,000, how many of those have a push device, and up to five examples.
   *
   * @throws ApiException 429 {@code PREVIEW_BUSY} when other previews are running, 422 {@code SEGMENT_TOO_HEAVY} when counting takes too long
   */
  public PreviewResult preview(UUID segmentId, Segment inline) {
    Segment s = resolve(segmentId, inline).segment();
    Instant now = clock.instant();
    SegmentQueryRepository.Preview p = query.preview(s, now, PREVIEW_CAP, SAMPLE);
    List<SampleView> sample = p.sample().stream().map(x -> new SampleView(x.id(), x.name(), mask(x.mobile()))).toList();
    return new PreviewResult(p.count().recipients(), p.count().exact(), p.count().withDevices(), sample);
  }

  /** The catalog of condition types the segment builder renders from. */
  public List<ConditionType.Entry> conditionTypes() { return ConditionType.catalog(); }

  /** {@code 9876543210} becomes {@code 98XXXXXX10}; a missing or short number shows nothing useful. */
  static String mask(String mobile) {
    if (mobile == null) return null;
    if (mobile.length() < 5) return "XXXX";
    return mobile.substring(0, 2) + "X".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 2);
  }

  private static ApiException taken() {
    return ApiException.conflict("SEGMENT_NAME_TAKEN", "Another segment already has this name", Map.of());
  }
}
