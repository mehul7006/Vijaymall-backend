package com.vijay.mall.notification.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.notification.infra.BroadcastRepository;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Admin announcements to chosen people, a role group or a segment. Creating a broadcast only records it (and returns at once);
 * {@link BroadcastProcessor} sends it in the background. The inbox kind of every message is {@code ANNOUNCEMENT}.
 */
@Service
public class BroadcastService {
  static final int MAX_TITLE = 80;
  static final int MAX_BODY = 500;
  static final int MAX_REF = 100;
  static final int MAX_LIST = 500;
  private static final Pattern MOBILE = Pattern.compile("\\d{10}");
  /** A segment's recipients are only estimated, up to this many; the real total is whatever the sender delivers. */
  public static final int ESTIMATE_CAP = 10_000;

  /**
   * What the admin asks for.
   * @param audience who to send to
   * @param userIds explicit user ids, only for {@code USERS}, at most 500
   * @param mobiles explicit 10-digit mobile numbers, only for {@code USERS}, at most 500
   * @param segmentId a saved segment, only for {@code SEGMENT}
   * @param segment inline conditions, only for {@code SEGMENT} (instead of segmentId)
   * @param title headline, 1 to 80 characters after trimming
   * @param body message text, 1 to 500 characters after trimming
   * @param ref optional free text for the app (at most 100 characters)
   */
  @Schema(name = "BroadcastRequest")
  public record Request(Audience audience, List<UUID> userIds, List<String> mobiles, UUID segmentId, Segment segment, String title,
                        String body, String ref) {}

  /**
   * A broadcast as the API shows it.
   * @param recipients people addressed. While {@code recipientsExact} is false it is an estimate (a big segment is counted only up to
   *                   10,000 and shown as "10,000+"); it never shows less than {@code delivered}, and when the broadcast ends it becomes
   *                   the number really delivered
   * @param recipientsExact false while recipients is only an estimate
   * @param delivered inbox messages written so far (the source of truth)
   * @param pushed devices a push was handed to so far; always 0 while no push provider is configured
   * @param pushFailedBatches batches in which a push call failed (their inbox messages were written anyway)
   * @param pushConfigured false when no push provider (Firebase) is configured: broadcasts then reach the inbox only
   */
  @Schema(name = "BroadcastView")
  public record View(UUID id, Audience audience, String title, String body, String ref, String status, long recipients, boolean recipientsExact,
                     long delivered, long pushed, int pushFailedBatches, boolean pushConfigured, UUID createdBy, Instant createdAt,
                     Instant startedAt, Instant finishedAt, List<String> skipped, UUID segmentId, Segment segment) {}

  /**
   * How many people an audience reaches right now.
   * @param audience the audience asked about
   * @param recipients eligible people
   * @param withDevices how many of them have at least one push device
   */
  public record Size(Audience audience, long recipients, long withDevices) {}

  private final BroadcastRepository store;
  private final ProfileService profiles;
  private final SegmentService segments;
  private final SegmentQueryRepository query;
  private final AuditService audit;
  private final Clock clock;
  private final int perHour;

  private RateLimiter limiter;
  private TransactionOperations tx;
  private PushSender push;

  /** Optional (plain unit tests run without it): makes recording the broadcast and its audit entry one transaction. */
  @Autowired(required = false)
  public void setTransactions(TransactionOperations tx) { this.tx = tx; }

  /** Optional (plain unit tests run without it): tells whether a push provider is configured, shown with every broadcast. */
  @Autowired(required = false)
  public void setPush(PushSender push) { this.push = push; }

  /** Optional (plain unit tests run without it): at most {@code app.notifications.broadcast-per-hour} broadcasts per admin per hour. */
  @Autowired(required = false)
  public void setLimiter(RateLimiter limiter) { this.limiter = limiter; }

  /** Creates the service (wired by Spring). */
  public BroadcastService(BroadcastRepository store, ProfileService profiles, SegmentService segments, SegmentQueryRepository query,
                          AuditService audit, Clock clock, @Value("${app.notifications.broadcast-per-hour:20}") int perHour) {
    this.store = store;
    this.profiles = profiles;
    this.segments = segments;
    this.query = query;
    this.audit = audit;
    this.clock = clock;
    this.perHour = perHour;
  }

  private View view(BroadcastRepository.Row r) {
    long recipients = r.recipientsExact() ? r.recipients() : Math.max(r.recipients(), r.delivered());
    return new View(r.id(), Audience.valueOf(r.audience()), r.title(), r.body(), r.ref(), r.status(), recipients, r.recipientsExact(), r.delivered(),
        r.pushed(), r.pushFailedBatches(), push != null && push.isConfigured(), r.createdBy(), r.createdAt(), r.startedAt(), r.finishedAt(),
        r.skipped(), r.segmentId(), r.segment());
  }

  private static ApiException invalid(Map<String, Object> fields) {
    return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Please check the highlighted fields", Map.of("fields", fields));
  }

  private static String trimmed(String raw, int max, String field, Map<String, Object> fields) {
    String t = raw == null ? "" : raw.strip();
    if (t.isEmpty() || t.length() > max) fields.put(field, "must be 1 to " + max + " characters");
    return t;
  }

  /**
   * Records a broadcast to be sent in the background.
   *
   * @return the queued broadcast
   * <p>The hourly quota is used only once the broadcast is certain to be recorded: after validation, the segment lookup and the
   * recipient resolution. A request that fails any of those costs nothing. The recipient count of a segment is an estimate (see
   * {@link View}); counting it happens before the recording transaction opens, so no connection is held during it.
   *
   * @throws ApiException 400 {@code VALIDATION_FAILED}, 404 for an unknown segment, 422 {@code NO_RECIPIENTS} when an explicit list
   *                      matched nobody, 422 {@code SEGMENT_TOO_HEAVY} or 429 {@code PREVIEW_BUSY} when the segment cannot be counted now,
   *                      429 {@code RATE_LIMITED} after too many broadcasts in an hour
   */
  public View enqueue(UUID actor, Request in) {
    Map<String, Object> fields = new LinkedHashMap<>();
    if (in.audience() == null) fields.put("audience", "is required");
    String title = trimmed(in.title(), MAX_TITLE, "title", fields);
    String body = trimmed(in.body(), MAX_BODY, "body", fields);
    String ref = in.ref() == null || in.ref().isBlank() ? null : in.ref().strip();
    if (ref != null && ref.length() > MAX_REF) fields.put("ref", "must be at most " + MAX_REF + " characters");
    List<UUID> userIds = in.userIds() == null ? List.of() : in.userIds();
    boolean emptyId = userIds.stream().anyMatch(Objects::isNull);
    List<String> mobiles = in.mobiles() == null ? List.of() : in.mobiles().stream().map(m -> m == null ? "" : m.strip()).toList();
    boolean users = in.audience() == Audience.USERS;
    if (users) {
      if (userIds.isEmpty() && mobiles.isEmpty()) fields.put("userIds", "give at least one user id or mobile number");
      if (userIds.size() > MAX_LIST) fields.put("userIds", "at most " + MAX_LIST + " ids");
      if (emptyId) fields.put("userIds", "must not contain empty values");
      if (mobiles.size() > MAX_LIST) fields.put("mobiles", "at most " + MAX_LIST + " numbers");
      if (!mobiles.stream().allMatch(m -> MOBILE.matcher(m).matches())) fields.put("mobiles", "each number must be 10 digits");
    } else if (!userIds.isEmpty() || !mobiles.isEmpty()) {
      fields.put("userIds", "only used when audience is USERS");
    }
    boolean segment = in.audience() == Audience.SEGMENT;
    if (!segment && (in.segmentId() != null || in.segment() != null)) fields.put("segment", "only used when audience is SEGMENT");
    if (!fields.isEmpty()) throw invalid(fields);

    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    long recipients;
    boolean exact = true;
    List<UUID> ids = null;
    List<String> skipped = List.of();
    SegmentService.Resolved resolved = null;
    if (users) {
      ProfileService.Recipients r = profiles.resolveRecipients(userIds, mobiles);
      if (r.ids().isEmpty()) throw ApiException.unprocessable("NO_RECIPIENTS", "None of the given people can receive a notification", Map.of("skipped", r.skipped()));
      ids = r.ids();
      skipped = r.skipped();
      recipients = ids.size();
    } else if (segment) {
      resolved = segments.resolve(in.segmentId(), in.segment());
      SegmentQueryRepository.Count c = query.count(resolved.segment(), now, ESTIMATE_CAP);
      recipients = c.recipients();
      exact = c.exact();
    } else {
      recipients = profiles.countRecipients(in.audience().roles());
    }
    if (limiter != null) limiter.check("broadcast:" + actor, perHour, 3600);
    final long n = recipients;
    final boolean isExact = exact;
    final List<UUID> toIds = ids;
    final List<String> toSkipped = skipped;
    final SegmentService.Resolved from = resolved;
    inTransaction(() -> {
      store.insert(id, in.audience().name(), title, body, ref, n, isExact, toIds, from == null ? null : from.segmentId(),
          from == null ? null : from.segment(), toSkipped, actor, now);
      audit.log(actor, "NOTIFICATION_BROADCAST", "notification_broadcast", id,
          "audience=" + in.audience() + " recipients=" + n + (isExact ? "" : "+ (estimate)") + " title=" + title);
      return id;
    });
    return get(id);
  }

  private <T> T inTransaction(Supplier<T> work) { return tx == null ? work.get() : tx.execute(_ -> work.get()); }

  /** One broadcast; 404 when unknown. */
  public View get(UUID id) { return view(store.find(id).orElseThrow(() -> ApiException.notFound("Broadcast"))); }

  /** Broadcasts, newest first. */
  public Page<View> list(int page, int size) {
    int s = Page.clampSize(size);
    int p = Math.max(0, page);
    Page<BroadcastRepository.Row> rows = store.page(p, s);
    return new Page<>(rows.items().stream().map(this::view).toList(), p, s, rows.total());
  }

  /** How many people a role audience reaches (and how many of them have a push device). Segments use the segment preview instead. */
  public Size size(Audience audience) {
    if (!audience.isRoleGroup()) {
      throw ApiException.validation("audience", audience == Audience.SEGMENT ? "use the segment preview to size a SEGMENT" : "USERS has no size to estimate");
    }
    return new Size(audience, profiles.countRecipients(audience.roles()), profiles.countRecipientsWithDevices(audience.roles()));
  }
}
