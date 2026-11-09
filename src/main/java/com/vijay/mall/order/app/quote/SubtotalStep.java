package com.vijay.mall.order.app.quote;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import java.util.ArrayList;
import java.util.List;
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

  @Override public int order() { return 100; }

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
    c.subtotal = c.lines.stream().mapToLong(PricedLine::lineTotalPaise).sum();
  }
}
