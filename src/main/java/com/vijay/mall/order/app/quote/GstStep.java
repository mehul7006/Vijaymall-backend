package com.vijay.mall.order.app.quote;

import com.vijay.mall.common.tax.GstCalculator;
import com.vijay.mall.common.tax.ProductGst;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteContext.LineGst;
import com.vijay.mall.order.domain.QuoteStep;
import com.vijay.mall.pos.app.PosCatalogService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;

/**
 * Quote step 550: splits the GST inside what the customer pays for each line, with the same rules as the counter bill. A line's net is its
 * total minus its coupon share and its share of the coin discount (spread over what every line still costs, largest remainder); the
 * taxable value is that net times 100 over (100 + rate), rounded half up, and the odd paisa of the tax goes to SGST. The delivery fee is
 * not part of any slab. Counter quotes (preset lines) are skipped: the counter bill does its own GST. Rates come from the pos module,
 * for all lines in one query.
 */
@Component
public class GstStep implements QuoteStep {
  private final PosCatalogService pos;

  /** Creates the step. */
  public GstStep(PosCatalogService pos) { this.pos = pos; }

  @Override public int order() { return 550; }

  @Override
  public void apply(QuoteContext c) {
    if (c.preset != null || c.lines.isEmpty()) return;
    Map<UUID, ProductGst> rates = pos.gstFor(c.lines.stream().map(l -> l.productId()).distinct().toList());
    long[] gross = c.lines.stream().mapToLong(l -> l.lineTotalPaise()).toArray();
    long[] net = GstCalculator.netPerLine(gross, c.couponShares(), c.couponDiscount, c.coinDiscount);
    List<LineGst> out = IntStream.range(0, gross.length).mapToObj(i -> {
      ProductGst g = rates.getOrDefault(c.lines.get(i).productId(), ProductGst.UNSET);
      return new LineGst(g.gstPercent(), g.hsn(), g.configured(), GstCalculator.split(net[i], g.gstPercent()));
    }).toList();
    c.gstLines = out;
  }
}
