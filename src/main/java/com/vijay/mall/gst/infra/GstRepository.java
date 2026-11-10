package com.vijay.mall.gst.infra;

import com.vijay.mall.gst.domain.GstRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes the four GST master tables ({@code gst_hsn_rate}, {@code gst_keyword}, {@code gst_rate_card},
 * {@code gst_master_audit}). Plain SQL through {@link JdbcClient}, like {@code PurchaseRepository}. The whole master is small
 * (about 6,500 rows), so the service loads it all into memory; there is no paging query here.
 */
@Repository
public class GstRepository {
  private final JdbcClient jdbc;

  GstRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private static GstRow.Hsn hsn(ResultSet r, int row) throws SQLException {
    return new GstRow.Hsn(r.getObject("id", UUID.class), r.getInt("row_no"), r.getString("hsn"), r.getString("chapter"),
        r.getString("tariff_description"), r.getString("product_description"), r.getString("name_gu"),
        r.getString("classification_conditions"), r.getString("condition_key"), r.getString("condition_label"),
        r.getBigDecimal("gst_percent"), r.getBigDecimal("cgst_percent"), r.getBigDecimal("sgst_percent"),
        r.getBigDecimal("igst_percent"), r.getBigDecimal("cess_percent"), r.getString("tax_type"), r.getString("schedule"),
        r.getString("notification_reference"), r.getString("source_reference"), r.getObject("effective_from", LocalDate.class),
        r.getString("verification_status"), r.getString("note"), r.getBoolean("active"),
        r.getObject("updated_at", OffsetDateTime.class).toInstant());
  }

  private static GstRow.Keyword keyword(ResultSet r, int row) throws SQLException {
    return new GstRow.Keyword(r.getObject("id", UUID.class), r.getInt("row_no"), r.getString("name"), r.getString("name_gu"),
        r.getString("keywords"), r.getString("hsn"), r.getString("condition_key"), r.getString("department"),
        r.getString("subcategory"), r.getString("verification_status"));
  }

  private static GstRow.Rate rate(ResultSet r, int row) throws SQLException {
    return new GstRow.Rate(r.getObject("id", UUID.class), r.getString("code"), r.getInt("sort_order"),
        r.getBigDecimal("gst_percent"), r.getBigDecimal("cgst_percent"), r.getBigDecimal("sgst_percent"),
        r.getBigDecimal("igst_percent"), r.getBigDecimal("cess_percent"), r.getString("tax_type"), r.getString("note"),
        r.getBoolean("active"));
  }

  /** Every HSN row, active or not (the matcher ignores the inactive ones; the review list shows them). */
  public List<GstRow.Hsn> allHsn() {
    return jdbc.sql("select * from gst_hsn_rate order by hsn, row_no").query(GstRepository::hsn).list();
  }

  /** Every active keyword row. */
  public List<GstRow.Keyword> activeKeywords() {
    return jdbc.sql("select * from gst_keyword where active order by row_no").query(GstRepository::keyword).list();
  }

  /** The rate card, selectable codes only, in display order. */
  public List<GstRow.Rate> activeRates() {
    return jdbc.sql("select * from gst_rate_card where active order by sort_order").query(GstRepository::rate).list();
  }

  /** One HSN row by id. */
  public Optional<GstRow.Hsn> findHsn(UUID id) {
    return jdbc.sql("select * from gst_hsn_rate where id = :id").param("id", id).query(GstRepository::hsn).optional();
  }

  /**
   * Cheap change detector for the other servers: row counts and the newest update time of the three tables. A value that
   * differs from the one seen at the last load means "reload the in-memory master".
   */
  public String fingerprint() {
    return jdbc.sql("""
        select (select count(*) || ':' || coalesce(max(updated_at)::text, '') from gst_hsn_rate) || '|'
            || (select count(*) || ':' || coalesce(max(updated_at)::text, '') from gst_keyword) || '|'
            || (select count(*) || ':' || coalesce(max(updated_at)::text, '') from gst_rate_card)""")
        .query(String.class).single();
  }

  /** Inserts a new HSN row; the row number is the next free one. */
  public void insertHsn(GstRow.Hsn h, UUID actor, Instant now) {
    jdbc.sql("""
        insert into gst_hsn_rate (id, row_no, hsn, chapter, tariff_description, product_description, name_gu,
          classification_conditions, condition_key, condition_label, gst_percent, cgst_percent, sgst_percent, igst_percent,
          cess_percent, tax_type, schedule, notification_reference, source_reference, effective_from, verification_status,
          note, active, updated_at, updated_by)
        values (:id, (select coalesce(max(row_no), 0) + 1 from gst_hsn_rate), :hsn, :chapter, :tariff, :descr, :nameGu,
          :conditions, :cond, :label, :gst, :cgst, :sgst, :gst, :cess, :taxType, :schedule, :notification, :source, :from,
          :verification, :note, true, :now, :actor)""")
        .param("id", h.id()).param("hsn", h.hsn()).param("chapter", h.chapter()).param("tariff", h.tariffDescription())
        .param("descr", h.productDescription()).param("nameGu", h.nameGu()).param("conditions", h.classificationConditions())
        .param("cond", h.conditionKey()).param("label", h.conditionLabel()).param("gst", h.gstPercent())
        .param("cgst", h.cgstPercent()).param("sgst", h.sgstPercent()).param("cess", h.cessPercent())
        .param("taxType", h.taxType()).param("schedule", h.schedule()).param("notification", h.notificationReference())
        .param("source", h.sourceReference()).param("from", h.effectiveFrom()).param("verification", h.verification())
        .param("note", h.note()).param("now", Timestamp.from(now)).param("actor", actor).update();
  }

  /** Overwrites the editable fields of an HSN row (not its id or row number); 0 when the id is unknown. */
  public int updateHsn(GstRow.Hsn h, UUID actor, Instant now) {
    return jdbc.sql("""
        update gst_hsn_rate set hsn = :hsn, chapter = :chapter, product_description = :descr, name_gu = :nameGu, condition_key = :cond,
          condition_label = :label, gst_percent = :gst, cgst_percent = :cgst, sgst_percent = :sgst, igst_percent = :gst,
          cess_percent = :cess, tax_type = :taxType, notification_reference = :notification, effective_from = :from,
          verification_status = :verification, note = :note, active = :active, updated_at = :now, updated_by = :actor
        where id = :id""")
        .param("id", h.id()).param("hsn", h.hsn()).param("chapter", h.chapter()).param("descr", h.productDescription())
        .param("nameGu", h.nameGu()).param("cond", h.conditionKey()).param("label", h.conditionLabel()).param("gst", h.gstPercent())
        .param("cgst", h.cgstPercent()).param("sgst", h.sgstPercent()).param("cess", h.cessPercent())
        .param("taxType", h.taxType()).param("notification", h.notificationReference()).param("from", h.effectiveFrom())
        .param("verification", h.verification()).param("note", h.note()).param("active", h.active())
        .param("now", Timestamp.from(now)).param("actor", actor).update();
  }

  /** Adds an item name under an HSN code unless that name already exists for it (case-insensitive). Returns rows added. */
  public int addKeywordIfNew(String name, String hsn, UUID actor, Instant now) {
    return jdbc.sql("""
        insert into gst_keyword (id, row_no, name, keywords, hsn, condition_key, department, subcategory, verification_status,
          active, updated_at, updated_by)
        select cast(:id as uuid), (select coalesce(max(row_no), 0) + 1 from gst_keyword), cast(:name as text), lower(cast(:name as text)),
          cast(:hsn as text), '', '', '', 'VERIFY_PRODUCT', true, cast(:now as timestamptz), cast(:actor as uuid)
        where not exists (select 1 from gst_keyword where hsn = :hsn and lower(name) = lower(:name))""")
        .param("id", UUID.randomUUID()).param("name", name).param("hsn", hsn)
        .param("now", Timestamp.from(now)).param("actor", actor).update();
  }

  /** True when at least one active keyword still points at this HSN code (checked before the code of a row is changed). */
  public boolean keywordsPointTo(String hsn) {
    return jdbc.sql("select exists (select 1 from gst_keyword where hsn = :h and active)").param("h", hsn)
        .query(Boolean.class).single();
  }

  /** True when another active row has this HSN code (so a keyword pointing at it keeps working). */
  public boolean otherActiveRow(String hsn, UUID exceptId) {
    return jdbc.sql("select exists (select 1 from gst_hsn_rate where hsn = :h and active and id <> :id)")
        .param("h", hsn).param("id", exceptId).query(Boolean.class).single();
  }

  /** Writes one structured audit line (old and new value as JSON text). */
  public void audit(UUID actor, String action, String entity, UUID entityId, String oldJson, String newJson, Instant now) {
    jdbc.sql("""
        insert into gst_master_audit (id, at, actor_id, action, entity, entity_id, old_value, new_value)
        values (:id, :at, :actor, :action, :entity, :entityId, cast(:old as jsonb), cast(:new as jsonb))""")
        .param("id", UUID.randomUUID()).param("at", Timestamp.from(now)).param("actor", actor)
        .param("action", action).param("entity", entity).param("entityId", entityId).param("old", oldJson)
        .param("new", newJson).update();
  }
}
