package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.OfflineExceptionKind;
import com.vijay.mall.pos.domain.OfflineFinding;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The differences found in offline bills, waiting for (or past) a manager's review. */
@Repository
public class OfflineExceptionRepository {
  /** One exception with the bill it belongs to, as the manager sees it. */
  public record Row(UUID id, UUID saleId, String invoiceNo, UUID cashierId, String cashierName, Instant soldAt, OfflineExceptionKind kind,
                    String detail, UUID productId, String productName, String expected, String actual, Instant createdAt,
                    UUID reviewedBy, Instant reviewedAt, String reviewNote) {}

  private static final String SELECT = "select e.id, e.sale_id, s.invoice_no, s.cashier_id, coalesce(nullif(u.name, ''), u.email) as cashier_name, "
      + "s.created_at as sold_at, e.kind, e.detail, e.product_id, p.name_en, e.expected_value, e.actual_value, e.created_at, e.reviewed_by, "
      + "e.reviewed_at, e.review_note from pos_offline_exception e join pos_sale s on s.id = e.sale_id "
      + "left join users u on u.id = s.cashier_id left join product p on p.id = e.product_id ";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc JDBC client used for the plain SQL queries
   */
  public OfflineExceptionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static Instant instant(Timestamp t) { return t == null ? null : t.toInstant(); }

  private static Row map(ResultSet r) throws SQLException {
    return new Row(r.getObject("id", UUID.class), r.getObject("sale_id", UUID.class), r.getString("invoice_no"),
        r.getObject("cashier_id", UUID.class), r.getString("cashier_name"), instant(r.getTimestamp("sold_at")),
        OfflineExceptionKind.valueOf(r.getString("kind")), r.getString("detail"), r.getObject("product_id", UUID.class),
        r.getString("name_en"), r.getString("expected_value"), r.getString("actual_value"), instant(r.getTimestamp("created_at")),
        r.getObject("reviewed_by", UUID.class), instant(r.getTimestamp("reviewed_at")), r.getString("review_note"));
  }

  /** Stores one finding for a bill and marks the bill as needing a review. */
  public void insert(UUID saleId, OfflineFinding f, Instant now) {
    jdbc.sql("insert into pos_offline_exception (id, sale_id, kind, detail, product_id, expected_value, actual_value, created_at) "
            + "values (:id, :sale, :kind, :detail, :product, :expected, :actual, :at)")
        .param("id", UUID.randomUUID()).param("sale", saleId).param("kind", f.kind().name()).param("detail", f.detail())
        .param("product", f.productId()).param("expected", f.expected()).param("actual", f.actual()).param("at", Timestamp.from(now)).update();
    jdbc.sql("update pos_sale set needs_review = true where id = :id").param("id", saleId).update();
  }

  /** The findings stored for one bill, oldest first. */
  public List<OfflineFinding> forSale(UUID saleId) {
    return jdbc.sql("select kind, detail, product_id, expected_value, actual_value from pos_offline_exception where sale_id = :id order by created_at, id")
        .param("id", saleId).query((r, _) -> new OfflineFinding(OfflineExceptionKind.valueOf(r.getString("kind")), r.getString("detail"),
            r.getObject("product_id", UUID.class), r.getString("expected_value"), r.getString("actual_value"))).list();
  }

  /** Open (not yet reviewed) or reviewed exceptions, newest first. */
  public List<Row> page(boolean reviewed, int page, int size) {
    return jdbc.sql(SELECT + "where (e.reviewed_at is not null) = :reviewed order by e.created_at desc, e.id limit :l offset :o")
        .param("reviewed", reviewed).param("l", size).param("o", (long) page * size).query((r, _) -> map(r)).list();
  }

  /** How many exceptions there are in the reviewed or the open list; used for paging. */
  public long count(boolean reviewed) {
    return jdbc.sql("select count(*) from pos_offline_exception where (reviewed_at is not null) = :reviewed").param("reviewed", reviewed)
        .query(Long.class).single();
  }

  /** One exception with its bill details. */
  public Optional<Row> find(UUID id) { return jdbc.sql(SELECT + "where e.id = :id").param("id", id).query((r, _) -> map(r)).optional(); }

  /** Marks the exception reviewed; false when it was already reviewed or does not exist. */
  public boolean review(UUID id, UUID reviewer, String note, Instant now) {
    return jdbc.sql("update pos_offline_exception set reviewed_by = :by, reviewed_at = :at, review_note = :note where id = :id and reviewed_at is null")
        .param("by", reviewer).param("at", Timestamp.from(now)).param("note", note).param("id", id).update() == 1;
  }

  /** Exceptions nobody has reviewed yet. */
  public long openCount() {
    return jdbc.sql("select count(*) from pos_offline_exception where reviewed_at is null").query(Long.class).single();
  }

  /** Exceptions nobody has reviewed yet that were recorded before {@code cutoff}. */
  public long openOlderThan(Instant cutoff) {
    return jdbc.sql("select count(*) from pos_offline_exception where reviewed_at is null and created_at < :c").param("c", Timestamp.from(cutoff))
        .query(Long.class).single();
  }
}
