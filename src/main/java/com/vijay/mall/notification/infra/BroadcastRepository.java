package com.vijay.mall.notification.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.notification.domain.Segment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores admin broadcasts (table {@code notification_broadcast}) and writes their inbox rows. Each batch of inbox rows and the
 * progress marker commit together, and the unique (broadcast, user) index makes a resumed broadcast skip the people it already reached.
 */
@Repository
public class BroadcastRepository {
  /** Inbox kind of every broadcast message. */
  public static final String KIND = "ANNOUNCEMENT";

  /**
   * One broadcast as the admin sees it.
   * @param id broadcast id
   * @param audience who it goes to
   * @param title headline
   * @param body message text
   * @param ref optional free text for the app (for example a screen to open), may be null
   * @param status {@code QUEUED}, {@code SENDING}, {@code DONE} or {@code FAILED}
   * @param recipients people it was addressed to when it was created
   * @param delivered inbox rows written so far
   * @param pushed devices a push was handed to so far
   * @param createdBy the admin who sent it
   * @param createdAt when it was created
   * @param startedAt when sending began, null while queued
   * @param finishedAt when it ended, null while open
   * @param skipped ids and mobile numbers of an explicit list that matched nobody
   * @param segmentId the saved segment it was made from, null when none or inline
   * @param segment the snapshot of the conditions used, null unless the audience is {@code SEGMENT}
   */
  public record Row(UUID id, String audience, String title, String body, String ref, String status, long recipients, long delivered,
                    long pushed, UUID createdBy, Instant createdAt, Instant startedAt, Instant finishedAt, List<String> skipped,
                    UUID segmentId, Segment segment) {}

  /**
   * A broadcast picked up for sending.
   * @param id broadcast id
   * @param audience who it goes to
   * @param title headline
   * @param body message text
   * @param ref optional free text, may be null
   * @param recipientIds the sorted list for an explicit audience, null otherwise
   * @param lastUserId the last user already handled, null at the start
   * @param segment the conditions for a segment audience, null otherwise
   * @param startedAt when sending first began; segment conditions are measured from this moment on every attempt
   */
  public record Job(UUID id, String audience, String title, String body, String ref, List<UUID> recipientIds, UUID lastUserId,
                    Segment segment, Instant startedAt) {}

  private static final String COLUMNS = "id, audience, title, body, ref, status, recipients, delivered, pushed, created_by, created_at, "
      + "started_at, finished_at, skipped::text as skipped, segment_id, segment_rules::text as segment_rules";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  BroadcastRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp t = rs.getTimestamp(column);
    return t == null ? null : t.toInstant();
  }

  private Row row(ResultSet rs, int i) throws SQLException {
    String rules = rs.getString("segment_rules");
    return new Row(rs.getObject("id", UUID.class), rs.getString("audience"), rs.getString("title"), rs.getString("body"), rs.getString("ref"),
        rs.getString("status"), rs.getLong("recipients"), rs.getLong("delivered"), rs.getLong("pushed"), rs.getObject("created_by", UUID.class),
        instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"),
        List.of(json.readValue(rs.getString("skipped"), String[].class)), rs.getObject("segment_id", UUID.class),
        rules == null ? null : json.readValue(rules, Segment.class));
  }

  /** Saves a new queued broadcast. {@code recipientIds}, {@code segmentId} and {@code segment} may be null. */
  @Transactional
  public void insert(UUID id, String audience, String title, String body, String ref, long recipients, List<UUID> recipientIds,
                     UUID segmentId, Segment segment, List<String> skipped, UUID by, Instant now) {
    jdbc.sql("insert into notification_broadcast (id, audience, title, body, ref, status, recipients, recipient_ids, segment_id, segment_rules, "
        + "skipped, created_by, created_at, updated_at) values (:id, :audience, :title, :body, :ref, 'QUEUED', :n, cast(:ids as jsonb), "
        + ":sid, cast(:rules as jsonb), cast(:skipped as jsonb), :by, :now, :now)")
        .param("id", id).param("audience", audience).param("title", title).param("body", body).param("ref", ref).param("n", recipients)
        .param("ids", recipientIds == null ? null : json.writeValueAsString(recipientIds)).param("sid", segmentId)
        .param("rules", segment == null ? null : json.writeValueAsString(segment)).param("skipped", json.writeValueAsString(skipped))
        .param("by", by).param("now", Timestamp.from(now)).update();
  }

  /** One broadcast. */
  public Optional<Row> find(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from notification_broadcast where id = :id").param("id", id).query(this::row).optional();
  }

  /** Broadcasts newest first. */
  public Page<Row> page(int page, int size) {
    List<Row> rows = jdbc.sql("select " + COLUMNS + " from notification_broadcast order by created_at desc, id limit :n offset :o")
        .param("n", size).param("o", (long) page * size).query(this::row).list();
    long total = jdbc.sql("select count(*) from notification_broadcast").query(Long.class).single();
    return new Page<>(rows, page, size, total);
  }

  /**
   * Picks the oldest broadcast that is queued, or was left sending by a process that stopped (no progress since {@code staleBefore}),
   * and marks it sending. Broadcasts that already used {@code maxAttempts} attempts are marked failed instead. Safe with several
   * instances: a broadcast another instance is claiming is skipped.
   */
  @Transactional
  public Optional<Job> claim(Instant now, Instant staleBefore, int maxAttempts) {
    Timestamp at = Timestamp.from(now);
    Timestamp stale = Timestamp.from(staleBefore);
    jdbc.sql("update notification_broadcast set status = 'FAILED', finished_at = :now, updated_at = :now where attempts >= :max "
        + "and (status = 'QUEUED' or (status = 'SENDING' and updated_at < :stale))")
        .param("now", at).param("max", maxAttempts).param("stale", stale).update();
    return jdbc.sql("select id from notification_broadcast where status = 'QUEUED' or (status = 'SENDING' and updated_at < :stale) "
        + "order by created_at limit 1 for update skip locked").param("stale", stale).query(UUID.class).optional().map(id -> {
          jdbc.sql("update notification_broadcast set status = 'SENDING', attempts = attempts + 1, started_at = coalesce(started_at, :now), "
              + "updated_at = :now where id = :id").param("now", at).param("id", id).update();
          return jdbc.sql("select id, audience, title, body, ref, recipient_ids::text as ids, last_user_id, segment_rules::text as rules, started_at "
              + "from notification_broadcast where id = :id").param("id", id).query((rs, i) -> {
                String ids = rs.getString("ids");
                String rules = rs.getString("rules");
                return new Job(id, rs.getString("audience"), rs.getString("title"), rs.getString("body"), rs.getString("ref"),
                    ids == null ? null : List.of(json.readValue(ids, UUID[].class)), rs.getObject("last_user_id", UUID.class),
                    rules == null ? null : json.readValue(rules, Segment.class), instant(rs, "started_at"));
              }).single();
        });
  }

  /**
   * Writes one batch of inbox rows (one per user, skipping blocked users and users who already have this broadcast) and moves the
   * progress marker, in one transaction.
   *
   * @return the ids of the users who got a new inbox row now (the ones to push to)
   */
  @Transactional
  public List<UUID> deliverBatch(UUID broadcastId, UUID lastUserId, List<UUID> userIds, String title, String body, String ref, Instant now) {
    Timestamp at = Timestamp.from(now);
    List<UUID> fresh = jdbc.sql("insert into notification (id, user_id, title, body, kind, ref, created_at, broadcast_id) "
        + "select gen_random_uuid(), u.id, :title, :body, :kind, :ref, :now, :b from users u where u.id in (:ids) and u.status = 'ACTIVE' "
        + "on conflict (broadcast_id, user_id) where broadcast_id is not null do nothing returning user_id")
        .param("title", title).param("body", body).param("kind", KIND).param("ref", ref).param("now", at).param("b", broadcastId)
        .param("ids", userIds).query(UUID.class).list();
    jdbc.sql("update notification_broadcast set last_user_id = :last, delivered = delivered + :n, updated_at = :now where id = :id")
        .param("last", lastUserId).param("n", fresh.size()).param("now", at).param("id", broadcastId).update();
    return fresh;
  }

  /** Adds to the count of devices a push was handed to. */
  @Transactional
  public void addPushed(UUID id, int devices, Instant now) {
    jdbc.sql("update notification_broadcast set pushed = pushed + :n, updated_at = :now where id = :id")
        .param("n", devices).param("now", Timestamp.from(now)).param("id", id).update();
  }

  /** Marks the broadcast done. */
  @Transactional
  public void finish(UUID id, Instant now) {
    jdbc.sql("update notification_broadcast set status = 'DONE', finished_at = :now, updated_at = :now where id = :id")
        .param("now", Timestamp.from(now)).param("id", id).update();
  }
}
