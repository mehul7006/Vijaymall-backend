package com.vijay.mall.inventory.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.inventory.app.DefectiveService;
import com.vijay.mall.inventory.infra.DefectiveRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returned goods that came back with a "defective" claim. Admin and counter staff review the claims (/pos/stock/defective, open
 * to ADMIN and CASHIER); only the admin sees the whole report and makes the final write-off or return-to-supplier decision
 * (/admin/stock/defective, admin only).
 */
@RestController
public class DefectiveController {
  /**
   * Request body for a review: {@code decision} is {@code CONFIRM_DEFECTIVE} or {@code RESTOCK}; {@code note} is optional, at most 200 characters.
   */
  public record ReviewBody(@NotBlank String decision, String note) {}

  /**
   * Request body for the final decision: {@code action} is {@code WRITE_OFF} or {@code RETURN_TO_SUPPLIER}; {@code note} is optional, at most 200 characters.
   */
  public record ResolveBody(@NotBlank String action, String note) {}

  private final DefectiveService defective;

  /** Creates the controller with the defective-goods service. */
  public DefectiveController(DefectiveService defective) { this.defective = defective; }

  // ---------- staff and admin: review the claims ----------

  /** Claims waiting for a decision (status PENDING_REVIEW by default), newest first. */
  @GetMapping("/api/v1/pos/stock/defective")
  public Page<DefectiveRepository.Row> toReview(@RequestParam(defaultValue = "PENDING_REVIEW") String status, @RequestParam(required = false) UUID productId,
                                               @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return defective.list(status, productId, page, size);
  }

  /** Look at the goods and decide: CONFIRM_DEFECTIVE (moves to the defective list) or RESTOCK (not defective: added back to stock). Once per batch. */
  @PostMapping("/api/v1/pos/stock/defective/{id}/review")
  public DefectiveRepository.Row review(@PathVariable UUID id, @Valid @RequestBody ReviewBody b) {
    return defective.review(id, b.decision(), b.note(), CurrentUser.id(), CurrentUser.isAdmin());
  }

  // ---------- admin: the full report and the final decision ----------

  /** Batches, newest first. status: PENDING_REVIEW, HELD, WRITTEN_OFF, RETURNED_TO_SUPPLIER or RESTOCKED (leave out for all). */
  @GetMapping("/api/v1/admin/stock/defective")
  public Page<DefectiveRepository.Row> list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID productId,
                                            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return defective.list(status, productId, page, size);
  }

  /** Per product: units waiting for review and units held as confirmed defective. */
  @GetMapping("/api/v1/admin/stock/defective/summary")
  public List<DefectiveRepository.Summary> summary() { return defective.summary(); }

  /** Final decision on a confirmed-defective batch: WRITE_OFF or RETURN_TO_SUPPLIER. Never added back to sellable stock. */
  @PostMapping("/api/v1/admin/stock/defective/{id}/resolve")
  public DefectiveRepository.Row resolve(@PathVariable UUID id, @Valid @RequestBody ResolveBody b) {
    return defective.resolve(id, b.action(), b.note(), CurrentUser.id());
  }
}
