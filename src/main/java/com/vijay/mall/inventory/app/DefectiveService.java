package com.vijay.mall.inventory.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.infra.DefectiveRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods returned with a "defective" claim. A claim is not a fact, so each batch waits for a review (PENDING_REVIEW): it is not
 * on the shelf and not yet counted as defective. Staff or the owner then decide:
 * <ul>
 *   <li>confirm it is defective: the batch is HELD, and the owner later writes it off or sends it back to the supplier;</li>
 *   <li>it is not defective: the units are added back to sellable stock (RESTOCKED).</li>
 * </ul>
 * Goods confirmed defective are never added back to the sellable stock; if a confirmation was a mistake, correct the stock with
 * a normal stock adjustment.
 */
@Service
public class DefectiveService {
  private static final Logger log = LoggerFactory.getLogger(DefectiveService.class);
  public static final Set<String> REVIEW_DECISIONS = Set.of("CONFIRM_DEFECTIVE", "RESTOCK");
  public static final Set<String> RESOLVE_ACTIONS = Set.of("WRITE_OFF", "RETURN_TO_SUPPLIER");
  private static final Set<String> STATUSES = Set.of("PENDING_REVIEW", "HELD", "WRITTEN_OFF", "RETURNED_TO_SUPPLIER", "RESTOCKED");

  private final DefectiveRepository repo;
  private final InventoryService inventory;
  private final AuditService audit;
  private final Clock clock;

  public DefectiveService(DefectiveRepository repo, InventoryService inventory, AuditService audit, Clock clock) {
    this.repo = repo;
    this.inventory = inventory;
    this.audit = audit;
    this.clock = clock;
  }

  /** Puts returned goods aside to be reviewed (they are not added to the shelf stock). */
  @Transactional
  public void record(List<InventoryService.Item> items, String source, UUID refId, UUID actor, String note) {
    for (InventoryService.Item it : items) {
      if (it.qty() <= 0) continue;
      repo.insert(UUID.randomUUID(), it.productId(), it.qty(), source, refId, note == null || note.isBlank() ? null : note.strip(), actor, clock.instant());
      log.atInfo().setMessage("Returned goods put aside for review").addKeyValue("event", "defective-claimed").addKeyValue("source", source)
          .addKeyValue("productId", it.productId().toString()).addKeyValue("qty", it.qty()).log();
    }
  }

  /** status: PENDING_REVIEW, HELD, WRITTEN_OFF, RETURNED_TO_SUPPLIER or RESTOCKED; blank = all. Newest first. */
  public Page<DefectiveRepository.Row> list(String status, UUID productId, int page, int size) {
    String s = status == null || status.isBlank() ? null : status.strip().toUpperCase();
    if (s != null && !STATUSES.contains(s)) {
      throw ApiException.validation("status", "PENDING_REVIEW, HELD, WRITTEN_OFF, RETURNED_TO_SUPPLIER or RESTOCKED");
    }
    int p = Math.max(0, page);
    int n = Page.clampSize(size);
    return new Page<>(repo.page(s, productId, p, n), p, n, repo.count(s, productId));
  }

  /** Per product: units waiting for review and units held as confirmed defective. */
  public List<DefectiveRepository.Summary> summary() { return repo.openSummary(); }

  public long heldUnits() { return repo.heldUnits(); }

  public long pendingReviewUnits() { return repo.pendingReviewUnits(); }

  /**
   * Staff or the owner look at the goods and decide. CONFIRM_DEFECTIVE moves the batch to the defective list (HELD); RESTOCK
   * says it is fine after all and adds the units back to sellable stock. A batch is reviewed once.
   *
   * <p>Confirming goods as defective takes them out of sellable stock for good, so a counter staff member cannot confirm the
   * batch they took back themselves: another person (or the admin) must. Putting goods back on sale carries no such risk.
   *
   * @param id       the batch
   * @param decision {@code CONFIRM_DEFECTIVE} or {@code RESTOCK}
   * @param note     optional, at most 200 characters
   * @param actor    who reviews
   * @param admin    whether the reviewer is an admin
   * @return the batch after the decision
   */
  @Transactional
  public DefectiveRepository.Row review(UUID id, String decision, String note, UUID actor, boolean admin) {
    String d = decision == null ? "" : decision.strip().toUpperCase();
    if (!REVIEW_DECISIONS.contains(d)) throw ApiException.validation("decision", "CONFIRM_DEFECTIVE or RESTOCK");
    String n = checkedNote(note);
    DefectiveRepository.Row row = repo.find(id).orElseThrow(() -> ApiException.notFound("Defective batch"));
    if ("CONFIRM_DEFECTIVE".equals(d) && !admin && actor != null && actor.equals(row.createdBy())) {
      throw ApiException.unprocessable("DEFECTIVE_SELF_REVIEW", "Someone else must confirm goods you took back as defective", Map.of());
    }
    String status = "RESTOCK".equals(d) ? "RESTOCKED" : "HELD";
    if (!repo.review(id, status, n, actor, clock.instant())) {
      throw ApiException.conflict("DEFECTIVE_ALREADY_REVIEWED", "This batch has already been reviewed", Map.of("status", currentStatus(id, row)));
    }
    if ("RESTOCK".equals(d)) inventory.adjust(row.productId(), row.qty(), "DEFECTIVE_REVIEW_RESTOCK", actor, id);
    audit.log(actor, "DEFECTIVE_" + d, "defective_stock", id, row.productName() + " x" + row.qty());
    return repo.find(id).orElseThrow();
  }

  /** The owner's final decision on a confirmed-defective batch: WRITE_OFF or RETURN_TO_SUPPLIER. Only a HELD batch, once. */
  @Transactional
  public DefectiveRepository.Row resolve(UUID id, String action, String note, UUID actor) {
    String a = action == null ? "" : action.strip().toUpperCase();
    if (!RESOLVE_ACTIONS.contains(a)) throw ApiException.validation("action", "WRITE_OFF or RETURN_TO_SUPPLIER");
    String n = checkedNote(note);
    DefectiveRepository.Row row = repo.find(id).orElseThrow(() -> ApiException.notFound("Defective batch"));
    String status = "WRITE_OFF".equals(a) ? "WRITTEN_OFF" : "RETURNED_TO_SUPPLIER";
    if (!repo.resolve(id, status, n, actor, clock.instant())) {
      throw ApiException.conflict("DEFECTIVE_NOT_HELD", "Only a batch confirmed as defective can be written off or sent back", Map.of("status", currentStatus(id, row)));
    }
    audit.log(actor, "DEFECTIVE_" + a, "defective_stock", id, row.productName() + " x" + row.qty());
    return repo.find(id).orElseThrow();
  }

  /** The batch's status as it is now (another reviewer may have changed it since it was read). */
  private String currentStatus(UUID id, DefectiveRepository.Row read) {
    return repo.find(id).map(DefectiveRepository.Row::status).orElse(read.status());
  }

  private static String checkedNote(String note) {
    String n = note == null ? "" : note.strip();
    if (n.length() > 200) throw ApiException.validation("note", "at most 200 characters");
    return n.isEmpty() ? null : n;
  }
}
