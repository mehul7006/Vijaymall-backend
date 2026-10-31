package com.vijay.mall.order.app.quote;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import org.springframework.stereotype.Component;

@Component
public class SubtotalStep implements QuoteStep {
  private final CatalogService catalog;

  public SubtotalStep(CatalogService catalog) { this.catalog = catalog; }

  @Override public int order() { return 100; }

  @Override
  public void apply(QuoteContext c) {
    c.lines = c.preset != null ? c.preset : catalog.price(c.request.items());
    c.subtotal = c.lines.stream().mapToLong(PricedLine::lineTotalPaise).sum();
  }
}
