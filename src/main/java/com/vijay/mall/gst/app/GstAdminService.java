package com.vijay.mall.gst.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.gst.domain.GstRow;
import com.vijay.mall.gst.infra.GstRepository;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The owner's edits of the GST master: add a row, change a row, switch a row off. Every edit writes {@code audit_log} (one line)
 * and {@code gst_master_audit} (old and new value) in the same transaction, and rebuilds the in-memory index only after the
 * transaction committed, so a rollback never leaves a rate in memory that is not stored.
 */
@Service
public class GstAdminService {
  private static final Logger log = LoggerFactory.getLogger(GstAdminService.class);

  private final GstRepository repo;
  private final GstService reads;
  private final AuditService audit;
  private final Clock clock;
  private final JsonMapper json;

  /** Creates the service. */
  public GstAdminService(GstRepository repo, GstService reads, AuditService audit, Clock clock, JsonMapper json) {
    this.repo = repo;
    this.reads = reads;
    this.audit = audit;
    this.clock = clock;
    this.json = json;
  }

  /** Adds a row and the item names given with it (names that already exist under the code are skipped). */
  @Transactional
  public GstService.RowView create(GstRowInput in, UUID actor) {
    GstRowValidator.Built b = GstRowValidator.build(UUID.randomUUID(), 0, in, null, clock);
    GstRow.Hsn h = b.row();
    repo.insertHsn(h, actor, clock.instant());
    addKeywords(b, actor);
    repo.audit(actor, "CREATE", "HSN_RATE", h.id(), null, toJson(h), clock.instant());
    audit.log(actor, "CREATE", "gst_hsn_rate", h.id(), describe(h));
    reloadAfterCommit();
    return GstViews.row(repo.findHsn(h.id()).orElseThrow());
  }

  /**
   * Changes a row; saving a switched-off row switches it back on. 404 for an unknown id; 409 {@code GST_HSN_IN_USE} when the code
   * changes while item names would be left without an active row.
   */
  @Transactional
  public GstService.RowView update(UUID rowId, GstRowInput in, UUID actor) {
    GstRow.Hsn old = repo.findHsn(rowId).orElseThrow(() -> ApiException.notFound("GST row"));
    GstRowValidator.Built b = GstRowValidator.build(rowId, old.rowNo(), in, old, clock);
    GstRow.Hsn h = b.row();
    if (!h.hsn().equals(old.hsn()) && old.active()) requireOtherRow(old);
    repo.updateHsn(h, actor, clock.instant());
    addKeywords(b, actor);
    String action = old.active() ? "UPDATE" : "ACTIVATE";
    repo.audit(actor, action, "HSN_RATE", rowId, toJson(old), toJson(h), clock.instant());
    audit.log(actor, action, "gst_hsn_rate", rowId, describe(old) + " -> " + describe(h));
    reloadAfterCommit();
    return GstViews.row(repo.findHsn(rowId).orElseThrow());
  }

  /** Switches a row off: it stops being suggested, the history stays. Switching off a row that is already off does nothing. */
  @Transactional
  public void deactivate(UUID rowId, UUID actor) {
    GstRow.Hsn old = repo.findHsn(rowId).orElseThrow(() -> ApiException.notFound("GST row"));
    if (!old.active()) return;
    requireOtherRow(old);
    GstRow.Hsn off = withActive(old, false);
    repo.updateHsn(off, actor, clock.instant());
    repo.audit(actor, "DEACTIVATE", "HSN_RATE", rowId, toJson(old), toJson(off), clock.instant());
    audit.log(actor, "DEACTIVATE", "gst_hsn_rate", rowId, describe(old));
    reloadAfterCommit();
  }

  private void requireOtherRow(GstRow.Hsn old) {
    if (repo.keywordsPointTo(old.hsn()) && !repo.otherActiveRow(old.hsn(), old.id())) {
      throw ApiException.conflict("GST_HSN_IN_USE", "Item names still point to HSN " + old.hsn() + ": add another row for it first",
          Map.of("hsn", old.hsn()));
    }
  }

  private void addKeywords(GstRowValidator.Built b, UUID actor) {
    for (String name : b.keywords()) repo.addKeywordIfNew(name, b.row().hsn(), actor, clock.instant());
  }

  private static GstRow.Hsn withActive(GstRow.Hsn h, boolean active) {
    return new GstRow.Hsn(h.id(), h.rowNo(), h.hsn(), h.chapter(), h.tariffDescription(), h.productDescription(), h.nameGu(),
        h.classificationConditions(), h.conditionKey(), h.conditionLabel(), h.gstPercent(), h.cgstPercent(), h.sgstPercent(),
        h.igstPercent(), h.cessPercent(), h.taxType(), h.schedule(), h.notificationReference(), h.sourceReference(),
        h.effectiveFrom(), h.verification(), h.note(), active, h.updatedAt());
  }

  /** Rebuilds the index once the transaction has committed (at once when there is no transaction). A failed rebuild is left to the 60-second check. */
  private void reloadAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() { reloadQuietly(); }
      });
    } else {
      reloadQuietly();
    }
  }

  private void reloadQuietly() {
    try {
      reads.reload();
    } catch (RuntimeException e) {
      log.warn("GST master reload after an edit failed, the next check retries: {}", e.toString());
    }
  }

  /** The editable fields as JSON for {@code gst_master_audit}: plain numbers, no derived values. */
  private String toJson(GstRow.Hsn h) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("hsn", h.hsn());
    m.put("description", h.productDescription());
    m.put("nameGu", h.nameGu());
    m.put("gstPercent", GstViews.clean(h.gstPercent()));
    m.put("cessRate", GstViews.clean(h.cessPercent()));
    m.put("taxType", h.taxType());
    m.put("condition", h.conditionKey());
    m.put("conditionLabel", h.conditionLabel());
    m.put("verification", h.verification());
    m.put("notificationReference", h.notificationReference());
    m.put("effectiveFrom", h.effectiveFrom());
    m.put("note", h.note());
    m.put("active", h.active());
    return json.writeValueAsString(m);
  }

  private static String describe(GstRow.Hsn h) {
    return "hsn " + h.hsn() + " " + h.conditionKey() + " " + GstViews.clean(h.gstPercent()) + "% " + h.taxType() + (h.active() ? "" : " (off)");
  }
}
