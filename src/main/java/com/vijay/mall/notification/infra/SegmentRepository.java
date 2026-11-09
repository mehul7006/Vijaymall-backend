package com.vijay.mall.notification.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.notification.domain.Rule;
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

/** Stores the segments the admin saved (table {@code notification_segment}). A deleted segment is only archived. */
@Repository
public class SegmentRepository {
  /**
   * A saved segment.
   * @param id segment id
   * @param name unique name (ignoring case) among live segments
   * @param description what the segment is for, may be empty
   * @param match {@code ALL} or {@code ANY}
   * @param rules the conditions
   * @param createdBy the admin who made it
   * @param createdAt when it was made
   * @param updatedAt when it was last changed
   */
  public record Row(UUID id, String name, String description, String match, List<Rule> rules, UUID createdBy, Instant createdAt, Instant updatedAt) {
    /** The conditions as a {@link Segment}. */
    public Segment segment() { return new Segment(match, rules); }
  }

  private static final String COLUMNS = "id, name, description, match_mode, rules::text as rules, created_by, created_at, updated_at";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  SegmentRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private Row row(ResultSet rs, int i) throws SQLException {
    return new Row(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"), rs.getString("match_mode"),
        List.of(json.readValue(rs.getString("rules"), Rule[].class)), rs.getObject("created_by", UUID.class),
        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
  }

  /** Saves a new segment. */
  @Transactional
  public void insert(UUID id, String name, String description, Segment s, UUID by, Instant now) {
    jdbc.sql("insert into notification_segment (id, name, description, match_mode, rules, created_by, created_at, updated_at) "
        + "values (:id, :name, :d, :m, cast(:rules as jsonb), :by, :now, :now)")
        .param("id", id).param("name", name).param("d", description).param("m", s.match()).param("rules", json.writeValueAsString(s.rules()))
        .param("by", by).param("now", Timestamp.from(now)).update();
  }

  /** Replaces name, description and conditions of a live segment; false when there is no such live segment. */
  @Transactional
  public boolean update(UUID id, String name, String description, Segment s, Instant now) {
    return jdbc.sql("update notification_segment set name = :name, description = :d, match_mode = :m, rules = cast(:rules as jsonb), "
        + "updated_at = :now where id = :id and not archived")
        .param("id", id).param("name", name).param("d", description).param("m", s.match()).param("rules", json.writeValueAsString(s.rules()))
        .param("now", Timestamp.from(now)).update() > 0;
  }

  /** Archives a live segment; false when there is no such live segment. */
  @Transactional
  public boolean archive(UUID id, Instant now) {
    return jdbc.sql("update notification_segment set archived = true, updated_at = :now where id = :id and not archived")
        .param("id", id).param("now", Timestamp.from(now)).update() > 0;
  }

  /** A live segment by id. */
  public Optional<Row> find(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from notification_segment where id = :id and not archived").param("id", id).query(this::row).optional();
  }

  /** True when another live segment already has this name (ignoring case). */
  public boolean nameTaken(String name, UUID except) {
    return jdbc.sql("select count(*) from notification_segment where lower(name) = lower(:n) and not archived and id <> :id")
        .param("n", name).param("id", except).query(Long.class).single() > 0;
  }

  /** Live segments, newest first. */
  public Page<Row> page(int page, int size) {
    List<Row> rows = jdbc.sql("select " + COLUMNS + " from notification_segment where not archived order by created_at desc, id limit :n offset :o")
        .param("n", size).param("o", (long) page * size).query(this::row).list();
    long total = jdbc.sql("select count(*) from notification_segment where not archived").query(Long.class).single();
    return new Page<>(rows, page, size, total);
  }
}
