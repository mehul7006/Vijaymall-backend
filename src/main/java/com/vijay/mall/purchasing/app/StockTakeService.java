package com.vijay.mall.purchasing.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
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
  public record Count(UUID productId, int countedQty) {}

  /** delta is counted minus what the system had: negative means stock was missing. */
  public record Change(UUID productId, String name, int before, int counted, int delta) {}

  public record Result(List<Change> changes, int unchanged) {}

  public static final int MAX_LINES = 500;

  private final InventoryService inventory;
  private final CatalogService catalog;
  private final AuditService audit;

  public StockTakeService(InventoryService inventory, CatalogService catalog, AuditService audit) {
    this.inventory = inventory;
    this.catalog = catalog;
    this.audit = audit;
  }

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
      int before = inventory.quantity(c.productId());
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
