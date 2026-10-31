package com.vijay.mall.order.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.order.domain.QuoteStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuoteServiceTest {
  private static QuoteStep step(int order, String name, List<String> log) {
    return new QuoteStep() {
      @Override public int order() { return order; }
      @Override public void apply(QuoteContext c) { log.add(name); c.warnings.add(name); }
    };
  }

  @Test
  void runsStepsInOrderNoMatterHowTheyAreListed() {
    List<String> log = new ArrayList<>();
    QuoteService service = new QuoteService(List.of(step(500, "total", log), step(100, "subtotal", log), step(300, "coins", log)));
    Quote q = service.quote(UUID.randomUUID(), new QuoteRequest(List.of(), null, null, null, Fulfilment.PICKUP));
    assertEquals(List.of("subtotal", "coins", "total"), log);
    assertEquals(List.of("subtotal", "coins", "total"), q.warnings());
  }

  @Test
  void aNewStepIsPickedUpWithoutChangingTheService() {
    List<String> log = new ArrayList<>();
    QuoteService service = new QuoteService(List.of(step(100, "a", log), step(150, "loyalty-bonus", log), step(200, "b", log)));
    service.quote(UUID.randomUUID(), new QuoteRequest(List.of(), null, null, null, null));
    assertEquals(List.of("a", "loyalty-bonus", "b"), log);
  }

  @Test
  void quoteLinesUsesTheGivenLinesAndNeverChargesDelivery() {
    var line = new com.vijay.mall.catalog.domain.PricedLine(UUID.randomUUID(), UUID.randomUUID(), "Rice", 2, 5000, 10000, true, true);
    List<QuoteContext> seen = new ArrayList<>();
    QuoteStep capture = new QuoteStep() {
      @Override public int order() { return 100; }
      @Override public void apply(QuoteContext c) {
        seen.add(c);
        c.lines = c.preset;
        c.subtotal = 10000;
      }
    };
    QuoteService service = new QuoteService(List.of(capture));
    Quote q = service.quoteLines(null, List.of(line), "SAVE", null, null);
    assertEquals(1, seen.size());
    assertEquals(Fulfilment.PICKUP, seen.get(0).request.fulfil());
    assertEquals("SAVE", seen.get(0).request.couponCode());
    assertEquals(1, q.lines().size());
    assertEquals(10000, q.subtotalPaise());
  }
}
