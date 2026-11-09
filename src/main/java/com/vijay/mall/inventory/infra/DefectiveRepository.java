package com.vijay.mall.inventory.infra;

import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The ledger of returned goods that are not fit to sell. */
@Repository
public class DefectiveRepository {
  /**
   * A batch of returned goods in the defective ledger, with its review and final-decision details.
   * <p>{@code status} is {@code PENDING_REVIEW}, {@code HELD}, {@code WRITTEN_OFF}, {@code RETURNED_TO_SUPPLIER} or {@code RESTOCKED};
   * {@code source} says where it came from and {@code refId} is the return or order behind it.
   */
  @Schema(name = "DefectiveRow")
  public record Row(UUID id, UUID productId, String productName, int qty, String source, UUID refId, String note, String status, Instant createdAt,
                    UUID createdBy, Instant reviewedAt, UUID reviewedBy, String reviewNote, Instant resolvedAt, UUID resolvedBy,
                    String resolveNote) {}

  /** Per product: units confirmed defective and held, units waiting for review, and the number of open batches. */
  public record Summary(UUID productId, String productName, long heldQty, long pendingReviewQty, long batches) {}

  private static final String SELECT = "select d.id, d.product_id, p.name_en, d.qty, d.source, d.ref_id, d.note, d.status, d.created_at, d.created_by, "
      + "d.reviewed_at, d.reviewed_by, d.review_note, d.resolved_at, d.resolved_by, d.resolve_note from defective_stock d join product p on p.id = d.product_id ";

  private final JdbcClient jdbc;

  /** Creates the repository. */
  public DefectiveRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** Adds a new batch; the table gives it the starting status (waiting for review). */
  public void insert(UUID id, UUID productId, int qty, String source, UUID refId, String note, UUID actor, Instant now) {
    jdbc.sql("insert into defective_stock (id, product_id, qty, source, ref_id, note, created_at, created_by) values (:id, :p, :q, :s, :r, :n, :t, :a)")
        .param("id", id).param("p", productId).param("q", qty).param("s", source).param("r", refId).param("n", note).param("t", Timestamp.from(now))
        .param("a", actor).update();
  }

  private static Row map(ResultSet r) throws SQLException {
    return new Row(r.getObject("id", UUID.class), r.getObject("product_id", UUID.class), r.getString("name_en"), r.getInt("qty"), r.getString("source"),
        r.getObject("ref_id", UUID.class), r.getString("note"), r.getString("status"), r.getTimestamp("created_at").toInstant(),
        r.getObject("created_by", UUID.class), r.getTimestamp("reviewed_at") == null ? null : r.getTimestamp("reviewed_at").toInstant(),
        r.getObject("reviewed_by", UUID.class), r.getString("review_note"), r.getTimestamp("resolved_at") == null ? null : r.getTimestamp("resolved_at").toInstant(),
        r.getObject("resolved_by", UUID.class), r.getString("resolve_note"));
  }

  /** Finds one batch by id, with the product's English name. */
  public Optional<Row> find(UUID id) { return jdbc.sql(SELECT + "where d.id = :id").param("id", id).query((r, _) -> map(r)).optional(); }

  /** status and productId may be null (no filter). Newest first. */
  public List<Row> page(String status, UUID productId, int page, int size) {
    return jdbc.sql(SELECT + "where (cast(:s as text) is null or d.status = :s) and (cast(:p as uuid) is null or d.product_id = :p) order by d.created_at desc limit :l offset :o")
        .param("s", status).param("p", productId).param("l", size).param("o", (long) page * size).query((r, _) -> map(r)).list();
  }

  /** Number of batches matching the same filters as {@link #page}, for paging. */
  public long count(String status, UUID productId) {
    return jdbc.sql("select count(*) from defective_stock d where (cast(:s as text) is null or d.status = :s) and (cast(:p as uuid) is null or d.product_id = :p)")
        .param("s", status).param("p", productId).query(Long.class).single();
  }

  /** What is waiting for a review or being held as confirmed defective, per product, biggest first. */
  public List<Summary> openSummary() {
    return jdbc.sql("select d.product_id, p.name_en, coalesce(sum(d.qty) filter (where d.status = 'HELD'), 0) as held, "
        + "coalesce(sum(d.qty) filter (where d.status = 'PENDING_REVIEW'), 0) as pending, count(*) as n "
        + "from defective_stock d join product p on p.id = d.product_id where d.status in ('HELD', 'PENDING_REVIEW') "
        + "group by d.product_id, p.name_en order by sum(d.qty) desc").query((r, _) ->
        new Summary(r.getObject("product_id", UUID.class), r.getString("name_en"), r.getLong("held"), r.getLong("pending"), r.getLong("n"))).list();
  }

  /** Total units currently held as confirmed defective. */
  public long heldUnits() { return unitsIn("HELD"); }

  /** Total units waiting for a review decision. */
  public long pendingReviewUnits() { return unitsIn("PENDING_REVIEW"); }

  private long unitsIn(String status) {
    return jdbc.sql("select coalesce(sum(qty), 0) from defective_stock where status = :s").param("s", status).query(Long.class).single();
  }

  /** The reviewer's decision on a claim: false when the batch was not waiting for review (already decided, or unknown). */
  public boolean review(UUID id, String newStatus, String note, UUID actor, Instant now) {
    return jdbc.sql("update defective_stock set status = :s, reviewed_at = :t, reviewed_by = :a, review_note = :n where id = :id and status = 'PENDING_REVIEW'")
        .param("s", newStatus).param("t", Timestamp.from(now)).param("a", actor).param("n", note).param("id", id).update() == 1;
  }

  /** Moves a HELD batch to its final status; false when it was not HELD (already dealt with, or unknown). */
  public boolean resolve(UUID id, String status, String note, UUID actor, Instant now) {
    return jdbc.sql("update defective_stock set status = :s, resolved_at = :t, resolved_by = :a, resolve_note = :n where id = :id and status = 'HELD'")
        .param("s", status).param("t", Timestamp.from(now)).param("a", actor).param("n", note).param("id", id).update() == 1;
  }
}
