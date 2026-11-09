package com.vijay.mall.order.app.quote;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Quote step 100: prices the lines (applying flash sale prices when one runs) and sums them into the subtotal. */
@Component
public class SubtotalStep implements QuoteStep {
  private final CatalogService catalog;

  /** Creates the step. */
  public SubtotalStep(CatalogService catalog) { this.catalog = catalog; }

  private FlashPricing flash;

  /** Optional (plain unit tests run without it): flash sale prices for online carts. */
  @Autowired(required = false)
  public void setFlash(FlashPricing flash) { this.flash = flash; }

  private InventoryService inventory;

  /** Optional (plain unit tests run without it): caps the quantity of a lot-priced item at what is left at its current price. */
  @Autowired(required = false)
  public void setInventory(InventoryService inventory) { this.inventory = inventory; }

  @Override public int order() { return 100; }

  /**
   * Online orders never mix two prices of one item: when a lot-priced item has more than one price in stock, a line may not take more
   * units than remain at the current price (units a flash sale covers are on top, they sell at the sale price). The refusal says how many
   * can be ordered; the customer orders the rest after the cheaper stock has sold.
   */
  private void capToLot(List<PricedLine> lines) {
    if (inventory == null) return;
    Map<UUID, Integer> wanted = new LinkedHashMap<>();
    Map<UUID, Integer> flashed = new HashMap<>();
    for (PricedLine l : lines) {
      wanted.merge(l.productId(), l.qty(), Integer::sum);
      if (l.flashSaleId() != null) flashed.merge(l.productId(), l.qty(), Integer::sum);
    }
    List<Map<String, Object>> over = new ArrayList<>();
    inventory.unitsAtThisPrice(wanted.keySet()).forEach((id, cap) -> {
      int allowed = cap + flashed.getOrDefault(id, 0);
      if (wanted.get(id) > allowed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", id.toString());
        m.put("maxQty", allowed);
        m.put("availableAtThisPrice", allowed);
        over.add(m);
      }
    });
    if (over.isEmpty()) return;
    int first = (Integer) over.getFirst().get("maxQty");
    String message = "Only " + first + " available at this price, order the rest after it sells";
    throw ApiException.unprocessable("QTY_EXCEEDS_LOT_PRICE", message,
        Map.of("fields", Map.of("qty", message), "items", over, "maxQty", first, "availableAtThisPrice", first));
  }

  /** Units that fit in the sale go at the sale price (one line), the rest at the normal price (a second line). */
  private List<PricedLine> withFlash(List<PricedLine> lines, UUID userId) {
    List<PricedLine> out = new ArrayList<>();
    for (PricedLine l : lines) {
      var applied = flash.apply(l.productId(), FlashPricing.Channel.ONLINE, l.unitPricePaise(), userId, l.qty());
      if (applied.isEmpty()) {
        out.add(l);
        continue;
      }
      var a = applied.get();
      out.add(l.split(a.saleQty(), a.saleUnitPricePaise(), a.flashSaleId(), l.unitPricePaise()));
      if (l.qty() > a.saleQty()) out.add(l.split(l.qty() - a.saleQty(), l.unitPricePaise(), null, 0));
    }
    return out;
  }

  @Override
  public void apply(QuoteContext c) {
    c.lines = c.preset != null ? c.preset : flash == null ? catalog.price(c.request.items()) : withFlash(catalog.price(c.request.items()), c.userId);
    if (c.preset == null) capToLot(c.lines);
    c.subtotal = c.lines.stream().mapToLong(PricedLine::lineTotalPaise).sum();
  }
}
