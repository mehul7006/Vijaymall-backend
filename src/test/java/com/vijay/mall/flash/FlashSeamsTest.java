package com.vijay.mall.flash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.FlashPricing.Applied;
import com.vijay.mall.common.FlashPricing.Channel;
import com.vijay.mall.order.app.quote.SubtotalStep;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The small seams the flash sale uses in the quote: SubtotalStep splitting a line, PricedLine.split and the quote line fields. */
class FlashSeamsTest {
  UUID product = UUID.randomUUID();
  UUID sale = UUID.randomUUID();
  UUID user = UUID.randomUUID();
  CatalogService catalog = mock(CatalogService.class);
  FlashPricing flash = mock(FlashPricing.class);

  private QuoteContext ctx(int qty) {
    var req = new QuoteRequest(List.of(new CatalogService.ItemRequest(product, qty)), null, null, null, Fulfilment.PICKUP);
    when(catalog.price(any())).thenReturn(List.of(new PricedLine(product, UUID.randomUUID(), "Rice", qty, 10000, 10000L * qty, true, true)));
    return new QuoteContext(user, req);
  }

  @Test
  void withoutTheFeatureTheLinesAreTheCatalogLines() {
    SubtotalStep step = new SubtotalStep(catalog);
    QuoteContext c = ctx(3);
    step.apply(c);
    assertThat(c.lines).hasSize(1);
    assertThat(c.subtotal).isEqualTo(30000);
    assertThat(c.toQuote().lines().get(0).flashSaleId()).isNull();
    assertThat(c.toQuote().lines().get(0).listPricePaise()).isEqualTo(10000);
    assertThat(c.toQuote().lines().get(0).flashDiscountPaise()).isZero();
  }

  @Test
  void aNonSaleProductIsUntouched() {
    SubtotalStep step = new SubtotalStep(catalog);
    step.setFlash(flash);
    when(flash.apply(any(), any(), anyLong(), any(), anyInt())).thenReturn(Optional.empty());
    QuoteContext c = ctx(2);
    step.apply(c);
    assertThat(c.lines).hasSize(1);
    assertThat(c.subtotal).isEqualTo(20000);
  }

  @Test
  void aQuantityLargerThanTheSaleLeftIsSplitIntoTwoExactLines() {
    SubtotalStep step = new SubtotalStep(catalog);
    step.setFlash(flash);
    when(flash.apply(eq(product), eq(Channel.ONLINE), eq(10000L), eq(user), eq(5))).thenReturn(Optional.of(new Applied(sale, UUID.randomUUID(), 7999, 2, 10000)));
    QuoteContext c = ctx(5);
    step.apply(c);
    assertThat(c.lines).hasSize(2);
    assertThat(c.lines.get(0).qty()).isEqualTo(2);
    assertThat(c.lines.get(0).unitPricePaise()).isEqualTo(7999);
    assertThat(c.lines.get(0).lineTotalPaise()).isEqualTo(15998);
    assertThat(c.lines.get(1).qty()).isEqualTo(3);
    assertThat(c.lines.get(1).unitPricePaise()).isEqualTo(10000);
    assertThat(c.subtotal).isEqualTo(15998 + 30000);
    Quote q = c.toQuote();
    Quote.Line sold = q.lines().get(0);
    assertThat(sold.flashSaleId()).isEqualTo(sale);
    assertThat(sold.listPricePaise()).isEqualTo(10000);
    assertThat(sold.flashDiscountPaise()).isEqualTo(2 * (10000 - 7999));
    Quote.Line rest = q.lines().get(1);
    assertThat(rest.flashSaleId()).isNull();
    assertThat(rest.flashDiscountPaise()).isZero();
    assertThat(q.lines().stream().mapToLong(Quote.Line::lineTotalPaise).sum()).isEqualTo(q.subtotalPaise());
  }

  @Test
  void aQuantityThatFitsStaysOneLine() {
    SubtotalStep step = new SubtotalStep(catalog);
    step.setFlash(flash);
    when(flash.apply(any(), any(), anyLong(), any(), anyInt())).thenReturn(Optional.of(new Applied(sale, UUID.randomUUID(), 9000, 4, 10000)));
    QuoteContext c = ctx(4);
    step.apply(c);
    assertThat(c.lines).hasSize(1);
    assertThat(c.subtotal).isEqualTo(36000);
  }

  @Test
  void counterLinesAreLeftToTheCounter() {
    SubtotalStep step = new SubtotalStep(catalog);
    step.setFlash(flash);
    var preset = List.of(new PricedLine(product, null, "Rice", 1, 9000, 9000, true, true));
    QuoteContext c = new QuoteContext(user, new QuoteRequest(List.of(), null, null, null, Fulfilment.PICKUP), preset);
    step.apply(c);
    assertThat(c.lines).isEqualTo(preset);
  }

  @Test
  void productViewsKeepTheirOldConstructors() {
    var offer = new FlashPricing.Offer(sale, "S", 100, 80, 20, 5, 1, null, null);
    var p = new com.vijay.mall.catalog.domain.Product(product, null, "a", "b", "", 100, 100, "kg", "", null, true, 5, false, true, true, product, 0, 0);
    assertThat(p.flash()).isNull();
    assertThat(p.withFlash(offer).flash()).isEqualTo(offer);
    var v = new CatalogService.Variant(product, "n", "p", "u", "l", 1, 1, null, true, 1);
    assertThat(v.flash()).isNull();
    assertThat(new CatalogService.ProductGroup(product, product, null, "a", "b", "", null, false, true, true, 0, 0, 1, true, List.of(v)).flash()).isNull();
  }
}
