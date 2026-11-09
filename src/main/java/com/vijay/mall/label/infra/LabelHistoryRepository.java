package com.vijay.mall.label.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.persistence.BoundedCount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The log of label prints: one row per variant per print request. */
@Repository
public class LabelHistoryRepository {
  /**
   * One row of the history.
   *
   * @param actorName the name of the person who printed, or null when the account has no name
   */
  public record Row(UUID id, Instant printedAt, UUID actorId, String actorName, UUID templateId, String templateName, UUID variantId,
                    String variantName, int copies, String mode) {}

  private final JdbcClient jdbc;

  /** Creates the repository. */
  public LabelHistoryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** Adds one row. */
  public void insert(Row r) {
    jdbc.sql("insert into label_print_history (id, printed_at, actor_id, template_id, template_name, variant_id, variant_name, copies, mode) "
        + "values (:id, :t, :a, :tid, :tn, :v, :vn, :c, :m)").param("id", r.id()).param("t", Timestamp.from(r.printedAt()))
        .param("a", r.actorId()).param("tid", r.templateId()).param("tn", r.templateName()).param("v", r.variantId())
        .param("vn", r.variantName()).param("c", r.copies()).param("m", r.mode()).update();
  }

  /** Newest first; {@code variantId} may be null for every variant. */
  public Page<Row> page(UUID variantId, int page, int size) {
    List<Row> fetched = jdbc.sql("select h.id, h.printed_at, h.actor_id, u.name as actor_name, h.template_id, h.template_name, h.variant_id, "
        + "h.variant_name, h.copies, h.mode from label_print_history h left join users u on u.id = h.actor_id "
        + "where (cast(:v as uuid) is null or h.variant_id = :v) order by h.printed_at desc, h.id limit :l offset :o")
        .param("v", variantId).param("l", size + 1).param("o", (long) page * size).query((r, _) -> new Row(r.getObject("id", UUID.class),
            r.getTimestamp("printed_at").toInstant(), r.getObject("actor_id", UUID.class), r.getString("actor_name"),
            r.getObject("template_id", UUID.class), r.getString("template_name"), r.getObject("variant_id", UUID.class),
            r.getString("variant_name"), r.getInt("copies"), r.getString("mode"))).list();
    return BoundedCount.assemble(fetched, page, size, () -> jdbc.sql("select count(*) from (select 1 from label_print_history h "
        + "where (cast(:v as uuid) is null or h.variant_id = :v) limit " + (BoundedCount.CAP + 1) + ") x").param("v", variantId)
        .query(Long.class).single());
  }
}
