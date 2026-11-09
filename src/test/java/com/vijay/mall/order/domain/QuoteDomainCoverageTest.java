package com.vijay.mall.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QuoteDomainCoverageTest {
  private static final UUID CAT_A = UUID.randomUUID();
  private static final UUID CAT_B = UUID.randomUUID();

  private static QuoteContext ctx() {
    return new QuoteContext(UUID.randomUUID(), new QuoteRequest(List.of(), null, null, null, null));
  }

  private static PricedLine line(UUID cat, long total) {
    return new PricedLine(UUID.randomUUID(), cat, "p", 1, total, total, true, true);
  }

  @Test
  void requestDefaultsWhenFieldsAreMissing() {
    var r = new QuoteRequest(List.of(), null, null, null, null);
    assertThat(r.mode()).isEqualTo(CoinRedemption.Mode.NONE);
    assertThat(r.pct()).isEqualTo(100);
    assertThat(r.fulfil()).isEqualTo(Fulfilment.DELIVERY);
    var set = new QuoteRequest(List.of(), "X", CoinRedemption.Mode.ALL, 40, Fulfilment.PICKUP);
    assertThat(set.mode()).isEqualTo(CoinRedemption.Mode.ALL);
    assertThat(set.pct()).isEqualTo(40);
    assertThat(set.fulfil()).isEqualTo(Fulfilment.PICKUP);
  }

  @Test
  void noCouponMeansNoCategoryBreakdown() {
    var c = ctx();
    c.lines = List.of(line(CAT_A, 100));
    c.couponDiscount = 50; // a discount without a code is ignored
    assertThat(c.toQuote().couponByCategory()).isEmpty();
  }

  @Test
  void aCodeWithZeroDiscountMeansNoCategoryBreakdown() {
    var c = ctx();
    c.lines = List.of(line(CAT_A, 100));
    c.couponCode = "X";
    c.couponDiscount = 0;
    assertThat(c.toQuote().couponByCategory()).isEmpty();
  }

  @Test
  void breakdownGroupsByCategorySkipsUncategorisedLinesAndUsesTheCouponShare() {
    var c = ctx();
    c.lines = List.of(line(CAT_A, 100), line(null, 70), line(CAT_B, 200), line(CAT_A, 50));
    c.subtotal = 420;
    c.couponCode = "X";
    c.couponDiscount = 30;
    c.couponByLine = List.of(10L, 0L, 0L, 20L);
    var q = c.toQuote();
    assertThat(q.couponByCategory()).containsExactly(
        new Quote.CategoryDiscount(CAT_A, 150, 30), new Quote.CategoryDiscount(CAT_B, 200, 0));
    assertThat(q.lines()).extracting(Quote.Line::couponDiscountPaise).containsExactly(10L, 0L, 0L, 20L);
  }

  @Test
  void aSplitOfTheWrongSizeIsIgnored() {
    var c = ctx();
    c.lines = List.of(line(CAT_A, 100), line(CAT_B, 100));
    c.couponCode = "X";
    c.couponDiscount = 10;
    c.couponByLine = List.of(10L);
    assertThat(c.toQuote().lines()).extracting(Quote.Line::couponDiscountPaise).containsExactly(0L, 0L);
  }
}
