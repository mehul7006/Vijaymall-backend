package com.vijay.mall.order.app.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.common.tax.GstSlab;
import com.vijay.mall.common.tax.ProductGst;
import com.vijay.mall.order.domain.Fulfilment;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.domain.BillCalculator;
import com.vijay.mall.pos.domain.SoldBy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The per-line GST split of an online quote: coupon and coin shares, rounding, mixed rates and the same figures as the counter bill. */
class GstStepTest {
  private final PosCatalogService pos = mock(PosCatalogService.class);
  private final GstStep step = new GstStep(pos);

  private static PricedLine line(UUID id, long total) {
    return new PricedLine(id, UUID.randomUUID(), "p", 1, total, total, true, true);
  }

  private static QuoteContext ctx(List<PricedLine> lines) {
    QuoteContext c = new QuoteContext(UUID.randomUUID(), new QuoteRequest(List.of(), null, null, null, Fulfilment.PICKUP));
    c.lines = lines;
    c.subtotal = lines.stream().mapToLong(PricedLine::lineTotalPaise).sum();
    return c;
  }

  private static ProductGst gst(String rate, String hsn) { return new ProductGst(GstRates.of(rate), hsn, true); }

  @Test
  void mixedRatesGiveEachLineItsOwnSplitAndOneSlabPerRate() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c0 = UUID.randomUUID(), d = UUID.randomUUID(), e = UUID.randomUUID();
    when(pos.gstFor(anyCollection())).thenReturn(Map.of(a, gst("18", "1006"), b, gst("40", "2202"), c0, gst("0.25", "0713"),
        d, gst("0", ""), e, ProductGst.UNSET));
    QuoteContext c = ctx(List.of(line(a, 11800), line(b, 14000), line(c0, 10025), line(d, 5000), line(e, 3333), line(a, 11800)));

    step.apply(c);
    Quote q = c.toQuote();

    assertThat(q.lines().get(0)).satisfies(l -> {
      assertThat(l.gstPercent()).isEqualByComparingTo("18");
      assertThat(l.hsn()).isEqualTo("1006");
      assertThat(l.taxablePaise()).isEqualTo(10000);
      assertThat(l.cgstPaise()).isEqualTo(900);
      assertThat(l.sgstPaise()).isEqualTo(900);
      assertThat(l.gstConfigured()).isTrue();
    });
    assertThat(q.lines().get(1).taxablePaise()).isEqualTo(10000);
    assertThat(q.lines().get(1).cgstPaise()).isEqualTo(2000);
    // 0.25%: 10025 -> taxable 10000, tax 25 = 12 + 13 (the odd paisa goes to SGST)
    assertThat(q.lines().get(2).taxablePaise()).isEqualTo(10000);
    assertThat(q.lines().get(2).cgstPaise()).isEqualTo(12);
    assertThat(q.lines().get(2).sgstPaise()).isEqualTo(13);
    assertThat(q.lines().get(3).taxablePaise()).isEqualTo(5000);
    assertThat(q.lines().get(3).gstConfigured()).isTrue();
    assertThat(q.lines().get(4)).satisfies(l -> {
      assertThat(l.gstPercent()).isEqualByComparingTo("0");
      assertThat(l.hsn()).isEmpty();
      assertThat(l.taxablePaise()).isEqualTo(3333);
      assertThat(l.gstConfigured()).isFalse();
    });

    assertThat(q.gstSlabs()).extracting(GstSlab::gstPercent).usingElementComparator(BigDecimal::compareTo)
        .containsExactly(new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("18"), new BigDecimal("40"));
    assertThat(q.gstSlabs().get(0).taxablePaise()).isEqualTo(8333);
    assertThat(q.gstSlabs().get(2)).satisfies(s -> {
      assertThat(s.taxablePaise()).isEqualTo(20000);
      assertThat(s.cgstPaise()).isEqualTo(1800);
      assertThat(s.sgstPaise()).isEqualTo(1800);
      assertThat(s.taxPaise()).isEqualTo(3600);
    });
    assertThat(q.taxableTotalPaise() + q.taxTotalPaise()).isEqualTo(q.subtotalPaise());
    assertThat(q.cgstTotalPaise() + q.sgstTotalPaise()).isEqualTo(q.taxTotalPaise());
    assertThat(q.gstSlabs().stream().mapToLong(GstSlab::taxPaise).sum()).isEqualTo(q.taxTotalPaise());
  }

  @Test
  void couponShareAndCoinShareLowerTheTaxableValueOfTheirLines() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    when(pos.gstFor(anyCollection())).thenReturn(Map.of(a, gst("18", "1"), b, gst("5", "2")));
    QuoteContext c = ctx(List.of(line(a, 11800), line(b, 10500)));
    c.couponCode = "X";
    c.couponDiscount = 1800;
    c.couponByLine = List.of(1800L, 0L);
    c.coinsUsed = 1;
    c.coinDiscount = 1000;
    c.deliveryFee = 4000;

    step.apply(c);
    Quote q = c.toQuote();

    // after the coupon the lines cost 10000 and 10500; the 1000 coin discount splits 488 / 512 (largest remainder over 20500)
    assertThat(q.lines().get(0).taxablePaise() + q.lines().get(0).cgstPaise() + q.lines().get(0).sgstPaise()).isEqualTo(10000 - 488);
    assertThat(q.lines().get(1).taxablePaise() + q.lines().get(1).cgstPaise() + q.lines().get(1).sgstPaise()).isEqualTo(10500 - 512);
    // the delivery fee is in no slab: taxable plus tax is what the customer pays for the goods
    assertThat(q.taxableTotalPaise() + q.taxTotalPaise()).isEqualTo(22300 - 1800 - 1000);
  }

  @Test
  void aCouponWithoutALineSplitIsSpreadOverEveryLine() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    when(pos.gstFor(anyCollection())).thenReturn(Map.of(a, gst("18", "1"), b, gst("18", "1")));
    QuoteContext c = ctx(List.of(line(a, 5000), line(b, 5000)));
    c.couponDiscount = 1001;

    step.apply(c);
    Quote q = c.toQuote();

    assertThat(q.taxableTotalPaise() + q.taxTotalPaise()).isEqualTo(10000 - 1001);
    assertThat(q.gstSlabs()).hasSize(1);
  }

  @Test
  void aLineFullyPaidByDiscountsHasNoTax() {
    UUID a = UUID.randomUUID();
    when(pos.gstFor(anyCollection())).thenReturn(Map.of(a, gst("18", "1")));
    QuoteContext c = ctx(List.of(line(a, 500)));
    c.couponDiscount = 500;
    c.couponByLine = List.of(500L);

    step.apply(c);

    assertThat(c.toQuote().taxTotalPaise()).isZero();
    assertThat(c.toQuote().taxableTotalPaise()).isZero();
  }

  @Test
  void counterQuotesAndEmptyBasketsAreLeftAlone() {
    QuoteContext preset = new QuoteContext(null, new QuoteRequest(List.of(), null, null, null, Fulfilment.PICKUP),
        List.of(line(UUID.randomUUID(), 100)));
    preset.lines = preset.preset;
    step.apply(preset);
    step.apply(ctx(List.of()));
    verify(pos, never()).gstFor(anyCollection());
    assertThat(preset.toQuote().gstSlabs()).isEmpty();
    assertThat(preset.toQuote().lines().get(0).gstConfigured()).isFalse();
    assertThat(preset.toQuote().lines().get(0).gstPercent()).isEqualByComparingTo("0");
  }

  @Test
  void aProductMissingFromTheLookupIsTreatedAsNotConfigured() {
    UUID a = UUID.randomUUID();
    when(pos.gstFor(anyCollection())).thenReturn(Map.of());
    QuoteContext c = ctx(List.of(line(a, 700)));
    step.apply(c);
    assertThat(c.toQuote().lines().get(0).gstConfigured()).isFalse();
    assertThat(c.toQuote().lines().get(0).taxablePaise()).isEqualTo(700);
  }

  @Test
  void orderIsBetweenTotalAndCoinEarning() {
    assertThat(step.order()).isEqualTo(550);
  }

  /** The online split must give the same paise as the counter bill for the same basket, rates and discounts. */
  @Test
  void theSamePaiseAsTheCounterBillForRandomBaskets() {
    Random random = new Random(42);
    List<BigDecimal> rates = GstRates.ALLOWED;
    for (int round = 0; round < 300; round++) {
      int n = 1 + random.nextInt(5);
      List<UUID> ids = new ArrayList<>();
      Map<UUID, ProductGst> table = new HashMap<>();
      List<PricedLine> lines = new ArrayList<>();
      List<BillCalculator.Draft> drafts = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        UUID id = UUID.randomUUID();
        BigDecimal rate = rates.get(random.nextInt(rates.size()));
        long price = 1 + random.nextInt(50000);
        int qty = 1 + random.nextInt(4);
        ids.add(id);
        table.put(id, new ProductGst(rate, "h", true));
        lines.add(new PricedLine(id, null, "p", qty, price, price * qty, true, true));
        drafts.add(new BillCalculator.Draft(id, "p", SoldBy.UNIT, qty, price, rate, "h"));
      }
      when(pos.gstFor(anyCollection())).thenReturn(table);
      QuoteContext c = ctx(lines);
      boolean split = random.nextBoolean();
      long[] shares = new long[n];
      if (split) {
        for (int i = 0; i < n; i++) shares[i] = random.nextBoolean() ? random.nextInt((int) Math.min(lines.get(i).lineTotalPaise(), 2000) + 1) : 0;
        c.couponDiscount = java.util.Arrays.stream(shares).sum();
        c.couponByLine = java.util.Arrays.stream(shares).boxed().toList();
      } else {
        c.couponDiscount = random.nextInt((int) Math.min(c.subtotal, 3000) + 1);
      }
      long remaining = c.subtotal - c.couponDiscount;
      c.coinDiscount = random.nextInt((int) Math.min(remaining, 3000) + 1);

      step.apply(c);
      BillCalculator.Bill bill = split ? BillCalculator.build(drafts, shares, c.coinDiscount)
          : BillCalculator.build(drafts, c.couponDiscount + c.coinDiscount);
      Quote q = c.toQuote();
      for (int i = 0; i < n; i++) {
        assertThat(q.lines().get(i).taxablePaise()).as("round " + round + " line " + i).isEqualTo(bill.lines().get(i).tax().taxable());
        assertThat(q.lines().get(i).cgstPaise()).isEqualTo(bill.lines().get(i).tax().cgst());
        assertThat(q.lines().get(i).sgstPaise()).isEqualTo(bill.lines().get(i).tax().sgst());
      }
      assertThat(q.taxableTotalPaise()).isEqualTo(bill.tax().taxable());
      assertThat(q.cgstTotalPaise()).isEqualTo(bill.tax().cgst());
      assertThat(q.sgstTotalPaise()).isEqualTo(bill.tax().sgst());
      assertThat(q.taxableTotalPaise() + q.taxTotalPaise()).isEqualTo(bill.totalPaise());
    }
  }
}
