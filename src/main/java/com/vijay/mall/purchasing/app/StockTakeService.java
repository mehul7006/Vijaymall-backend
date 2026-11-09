package com.vijay.mall.purchasing.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A physical stock count. Staff count the shelf and enter what they found; the system sets stock to that figure and keeps
 * the difference in the stock history (reason STOCK_TAKE) so shrinkage and mistakes are visible and nothing is silently lost.
 */
@Service
public class StockTakeService {
  /** What staff counted on the shelf for one product. */
  public record Count(UUID productId, int countedQty) {}

  /** delta is counted minus what the system had: negative means stock was missing. */
  @Schema(name = "StockTakeChange")
  public record Change(UUID productId, String name, int before, int counted, int delta) {}

  /** What a stock take changed, plus how many products already matched the count. */
  @Schema(name = "StockTakeResult")
  public record Result(List<Change> changes, int unchanged) {}

  /** Most products in one stock take. */
  public static final int MAX_LINES = 500;

  private final InventoryService inventory;
  private final CatalogService catalog;
  private final AuditService audit;

  /** Creates the service. */
  public StockTakeService(InventoryService inventory, CatalogService catalog, AuditService audit) {
    this.inventory = inventory;
    this.catalog = catalog;
    this.audit = audit;
  }

  /**
   * Sets the stock of each counted product to the counted figure; every difference is written to the stock history with the reason {@code STOCK_TAKE}.
   * <p>Each product's stock row is locked while it is counted, so a sale cannot change the quantity between reading it and applying the difference. Products already matching are skipped.
   * @param actor the person doing the count
   * @param note optional free text for the audit entry
   * @param counts 1 to {@link #MAX_LINES} counts, each product once and none negative
   * @return the products that changed and the number that matched
   * @throws ApiException validation error for a bad list or an unknown product
   */
  @Transactional
  public Result apply(UUID actor, String note, List<Count> counts) {
    if (counts == null || counts.isEmpty() || counts.size() > MAX_LINES) {
      throw ApiException.validation("items", "1 to " + MAX_LINES + " products");
    }
    Set<UUID> ids = new HashSet<>();
    for (Count c : counts) {
      if (c.productId() == null) throw ApiException.validation("productId", "is required");
      if (!ids.add(c.productId())) throw ApiException.validation("items", "a product appears twice");
      if (c.countedQty() < 0) throw ApiException.validation("countedQty", "must not be negative");
    }
    Map<UUID, String> names = catalog.names(ids);
    if (names.size() != ids.size()) throw ApiException.validation("productId", "a product does not exist");

    List<Change> changes = new ArrayList<>();
    int unchanged = 0;
    for (Count c : counts) {
      int before = inventory.quantityForUpdate(c.productId());
      int delta = c.countedQty() - before;
      if (delta == 0) {
        unchanged++;
        continue;
      }
      inventory.adjust(c.productId(), delta, "STOCK_TAKE", actor);
      changes.add(new Change(c.productId(), names.get(c.productId()), before, c.countedQty(), delta));
    }
    String why = note == null ? "" : note.strip();
    audit.log(actor, "STOCK_TAKE", "inventory", null, changes.size() + " changed, " + unchanged + " matched. " + why);
    return new Result(changes, unchanged);
  }
}
