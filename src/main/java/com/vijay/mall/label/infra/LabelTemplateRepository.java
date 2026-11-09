package com.vijay.mall.label.infra;

import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** Reads and writes the saved label templates; the field choices are kept as JSON text. */
@Repository
public class LabelTemplateRepository {
  private static final String SELECT = "select id, name, kind, width_mm, height_mm, sheet_columns, sheet_rows, gap_x_mm, gap_y_mm, "
      + "margin_left_mm, margin_top_mm, barcode_type, fields, built_in from label_template ";

  private final JdbcClient jdbc;
  private final JsonMapper json;

  /** Creates the repository. */
  public LabelTemplateRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  private LabelTemplate map(ResultSet r) throws SQLException {
    return new LabelTemplate(r.getObject("id", UUID.class), r.getString("name"), Kind.valueOf(r.getString("kind")), r.getDouble("width_mm"),
        r.getDouble("height_mm"), r.getInt("sheet_columns"), r.getInt("sheet_rows"), r.getDouble("gap_x_mm"), r.getDouble("gap_y_mm"),
        r.getDouble("margin_left_mm"), r.getDouble("margin_top_mm"), BarcodeType.valueOf(r.getString("barcode_type")),
        json.readValue(r.getString("fields"), LabelFields.class), r.getBoolean("built_in"));
  }

  /** One template by id. */
  public Optional<LabelTemplate> find(UUID id) { return jdbc.sql(SELECT + "where id = :id").param("id", id).query((r, _) -> map(r)).optional(); }

  /** True when another template (not {@code exceptId}) already has this name, ignoring upper and lower case. */
  public boolean nameTaken(String name, UUID exceptId) {
    return jdbc.sql("select count(*) from label_template where lower(name) = lower(:n) and id <> :id").param("n", name).param("id", exceptId)
        .query(Long.class).single() > 0;
  }

  /** A page of templates, built-in ones first, then by name. */
  public List<LabelTemplate> page(int page, int size) {
    return jdbc.sql(SELECT + "order by built_in desc, lower(name), id limit :l offset :o").param("l", size).param("o", (long) page * size)
        .query((r, _) -> map(r)).list();
  }

  /** How many templates there are. */
  public long count() { return jdbc.sql("select count(*) from label_template").query(Long.class).single(); }

  /** Inserts a new template. */
  public void insert(LabelTemplate t, Instant now) {
    jdbc.sql("insert into label_template (id, name, kind, width_mm, height_mm, sheet_columns, sheet_rows, gap_x_mm, gap_y_mm, margin_left_mm, "
        + "margin_top_mm, barcode_type, fields, built_in, created_at, updated_at) values (:id, :name, :kind, :w, :h, :c, :r, :gx, :gy, :ml, :mt, "
        + ":bt, :f, false, :now, :now)").params(values(t)).param("now", Timestamp.from(now)).update();
  }

  /** Overwrites a template. */
  public void update(LabelTemplate t, Instant now) {
    jdbc.sql("update label_template set name = :name, kind = :kind, width_mm = :w, height_mm = :h, sheet_columns = :c, sheet_rows = :r, "
        + "gap_x_mm = :gx, gap_y_mm = :gy, margin_left_mm = :ml, margin_top_mm = :mt, barcode_type = :bt, fields = :f, updated_at = :now "
        + "where id = :id").params(values(t)).param("now", Timestamp.from(now)).update();
  }

  /** Deletes a template. */
  public void delete(UUID id) { jdbc.sql("delete from label_template where id = :id").param("id", id).update(); }

  private Map<String, Object> values(LabelTemplate t) {
    return java.util.Map.ofEntries(
        Map.entry("id", t.id()), Map.entry("name", t.name()), Map.entry("kind", t.kind().name()),
        Map.entry("w", t.widthMm()), Map.entry("h", t.heightMm()), Map.entry("c", t.columns()),
        Map.entry("r", t.rows()), Map.entry("gx", t.gapXMm()), Map.entry("gy", t.gapYMm()),
        Map.entry("ml", t.marginLeftMm()), Map.entry("mt", t.marginTopMm()),
        Map.entry("bt", t.barcodeType().name()), Map.entry("f", json.writeValueAsString(t.fields())));
  }
}
