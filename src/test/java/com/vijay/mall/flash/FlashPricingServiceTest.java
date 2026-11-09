package com.vijay.mall.flash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.FlashPricing.Channel;
import com.vijay.mall.common.FlashPricing.Claim;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.outbox.OutboxService;
import com.vijay.mall.flash.app.FlashPricingService;
import com.vijay.mall.flash.infra.FlashRepository.Claimed;
import com.vijay.mall.flash.infra.FlashRepository.Item;
import com.vijay.mall.flash.infra.FlashRepository.Sale;
import com.vijay.mall.flash.infra.FlashRepository.SaleWithItems;
import com.vijay.mall.flash.infra.FlashRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class FlashPricingServiceTest {
  static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  FlashRepository repo = mock(FlashRepository.class);
  Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  FlashPricingService svc = new FlashPricingService(repo, clock);
  UUID saleId = UUID.randomUUID();
  UUID itemId = UUID.randomUUID();
  UUID product = UUID.randomUUID();
  UUID user = UUID.randomUUID();

  private Sale sale(String channel, Instant start, Instant end, String kind, long value, int limit) {
    return new Sale(saleId, "Diwali", "", "Banner", start, end, channel, kind, value, limit, null, null, NOW, NOW);
  }

  private Item item(String kind, long value, Integer max, int sold, Integer limit) {
    return new Item(itemId, saleId, product, kind, value, max, sold, limit);
  }

  private void open(Sale s, Item... items) {
    when(repo.openSales(any())).thenReturn(List.of(new SaleWithItems(s, List.of(items))));
  }

  @BeforeEach
  void setUp() { when(repo.soldQtys(any())).thenReturn(Map.of()); }

  @Test
  void noSaleMeansNoPrice() {
    when(repo.openSales(any())).thenReturn(List.of());
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 2)).isEmpty();
    assertThat(svc.offers(Map.of(product, 10000L))).isEmpty();
  }

  @Test
  void aRunningSalePricesTheWholeQuantityWhenNothingLimitsIt() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item(null, 0, null, 0, null));
    var a = svc.apply(product, Channel.ONLINE, 10000, user, 3).orElseThrow();
    assertThat(a.saleUnitPricePaise()).isEqualTo(8000);
    assertThat(a.saleQty()).isEqualTo(3);
    assertThat(a.flashSaleId()).isEqualTo(saleId);
    assertThat(a.listPricePaise()).isEqualTo(10000);
  }

  @Test
  void theItemOverrideAndTheCapDecideThePriceAndTheQuantity() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item("FIXED_PRICE", 7500, 10, 8, null));
    when(repo.soldQtys(any())).thenReturn(Map.of(itemId, 8));
    var a = svc.apply(product, Channel.POS, 10000, null, 5).orElseThrow();
    assertThat(a.saleUnitPricePaise()).isEqualTo(7500);
    assertThat(a.saleQty()).isEqualTo(2); // only 2 left of the cap
  }

  @Test
  void soldOutItemsSellAtTheNormalPrice() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item(null, 0, 10, 10, null));
    when(repo.soldQtys(any())).thenReturn(Map.of(itemId, 10));
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 1)).isEmpty();
    assertThat(svc.offers(Map.of(product, 10000L))).isEmpty();
  }

  @Test
  void thePerCustomerLimitUsesTheItemOverrideFirst() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 2), item(null, 0, null, 0, 5));
    when(repo.userQty(itemId, user)).thenReturn(4);
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 3).orElseThrow().saleQty()).isEqualTo(1);
    when(repo.userQty(itemId, user)).thenReturn(5);
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 3)).isEmpty();
    // a walk-in counter customer has no history to limit
    assertThat(svc.apply(product, Channel.POS, 10000, null, 3).orElseThrow().saleQty()).isEqualTo(3);
  }

  @Test
  void theSaleLimitAppliesWhenTheItemHasNone() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 2), item(null, 0, null, 0, null));
    when(repo.userQty(itemId, user)).thenReturn(1);
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 3).orElseThrow().saleQty()).isEqualTo(1);
  }

  @Test
  void anItemLimitOfZeroMeansNoLimitEvenWhenTheSaleHasOne() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 2), item(null, 0, null, 0, 0));
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 9).orElseThrow().saleQty()).isEqualTo(9);
    verify(repo, never()).userQty(any(), any());
  }

  @Test
  void notYetStartedEndedOrWrongChannelMeansNoPrice() {
    open(sale("BOTH", NOW.plusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item(null, 0, null, 0, null));
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 1)).isEmpty();
    open(sale("BOTH", NOW.minusSeconds(60), NOW, "PERCENT", 20, 0), item(null, 0, null, 0, null)); // ends exactly now
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 1)).isEmpty();
    open(sale("POS", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item(null, 0, null, 0, null));
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 1)).isEmpty();
    assertThat(svc.apply(product, Channel.POS, 10000, user, 1)).isPresent();
    open(sale("ONLINE", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item(null, 0, null, 0, null));
    assertThat(svc.apply(product, Channel.POS, 10000, user, 1)).isEmpty();
    assertThat(svc.apply(product, Channel.ONLINE, 10000, user, 1)).isPresent();
  }

  @Test
  void aListPriceThatLeavesNoDiscountOrZeroQuantityGivesNothing() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "FIXED_PRICE", 5000, 0), item(null, 0, null, 0, null));
    assertThat(svc.apply(product, Channel.ONLINE, 4000, user, 1)).isEmpty();
    assertThat(svc.apply(product, Channel.ONLINE, 9000, user, 0)).isEmpty();
  }

  @Test
  void offersCarryUnitsLeftAndTheCountdownEnd() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 10, 3), item(null, 0, 20, 5, null));
    when(repo.soldQtys(any())).thenReturn(Map.of(itemId, 5));
    FlashPricing.Offer o = svc.offers(Map.of(product, 20000L, UUID.randomUUID(), 100L)).get(product);
    assertThat(o.salePricePaise()).isEqualTo(18000);
    assertThat(o.listPricePaise()).isEqualTo(20000);
    assertThat(o.discountPercent()).isEqualTo(10);
    assertThat(o.unitsLeft()).isEqualTo(15);
    assertThat(o.perCustomerLimit()).isEqualTo(3);
    assertThat(o.endsAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(o.name()).isEqualTo("Diwali");
  }

  @Test
  void unlimitedOffersHaveNullUnitsLeft() {
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 10, 0), item(null, 0, null, 0, null));
    assertThat(svc.offers(Map.of(product, 20000L)).get(product).unitsLeft()).isNull();
  }

  @Test
  void thePriceLookupIsTimedAndTheDefinitionsAreCached() {
    var metrics = new SimpleMeterRegistry();
    svc.setMetrics(metrics);
    AppCaches caches = new AppCaches(clock);
    svc.setCaches(caches);
    open(sale("BOTH", NOW.minusSeconds(5), NOW.plusSeconds(60), "PERCENT", 20, 0), item(null, 0, null, 0, null));
    svc.apply(product, Channel.ONLINE, 10000, user, 1);
    svc.apply(product, Channel.ONLINE, 10000, user, 1);
    assertThat(metrics.find("mall.flash.price.lookup").timers().stream().mapToLong(t -> t.count()).sum()).isEqualTo(2);
    verify(repo, Mockito.times(1)).openSales(any());
    caches.invalidate(AppCaches.Name.FLASH);
    svc.apply(product, Channel.ONLINE, 10000, user, 1);
    verify(repo, Mockito.times(2)).openSales(any());
  }

  // ---------- counting ----------
  private Claim claim(int qty) { return new Claim(saleId, product, qty, 8000, 10000); }

  private Claimed claimed(Integer max, int sold, Integer itemLimit, int saleLimit) {
    return new Claimed(itemId, max, sold, itemLimit, saleLimit, "Diwali");
  }

  @Test
  void consumeRecordsTheLineAndCountsAfterwards() {
    var metrics = new SimpleMeterRegistry();
    svc.setMetrics(metrics);
    UUID order = UUID.randomUUID();
    when(repo.claim(eq(saleId), eq(product), eq(2), eq(List.of("ONLINE", "BOTH")), any())).thenReturn(Optional.of(claimed(null, 2, null, 0)));
    svc.consume(Channel.ONLINE, order, user, List.of(claim(2)));
    verify(repo).insertConsumption(itemId, saleId, product, "ONLINE", order, null, user, 2, 8000, 10000, NOW);
    assertThat(metrics.counter("mall.flash.units.sold", "channel", "ONLINE").count()).isEqualTo(2);
    assertThat(metrics.counter("mall.flash.revenue.paise", "channel", "ONLINE").count()).isEqualTo(16000);
    assertThat(metrics.counter("mall.flash.discount.paise", "channel", "ONLINE").count()).isEqualTo(4000);
  }

  @Test
  void counterBillsAreRecordedAgainstTheBill() {
    UUID bill = UUID.randomUUID();
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(claimed(null, 1, null, 0)));
    svc.consume(Channel.POS, bill, null, List.of(claim(1)));
    verify(repo).insertConsumption(itemId, saleId, product, "POS", null, bill, null, 1, 8000, 10000, NOW);
  }

  @Test
  void consumeIsRefusedWhenTheCapIsGone() {
    var metrics = new SimpleMeterRegistry();
    svc.setMetrics(metrics);
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.empty());
    when(repo.refusalReason(eq(saleId), anyList(), any())).thenReturn("LIMIT_REACHED");
    assertThatThrownBy(() -> svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(2))))
        .isInstanceOfSatisfying(FlashPricing.Refused.class, e -> {
          assertThat(e.reason()).isEqualTo("LIMIT_REACHED");
          assertThat(e.code()).isEqualTo("PRICE_CHANGED");
          assertThat(e.status().value()).isEqualTo(409);
        });
    verify(repo, never()).insertConsumption(any(), any(), any(), any(), any(), any(), any(), anyInt(), ArgumentMatchers.anyLong(),
        ArgumentMatchers.anyLong(), any());
    assertThat(metrics.counter("mall.flash.refused", "reason", "LIMIT_REACHED").count()).isEqualTo(1);
  }

  @Test
  void aSaleThatEndedIsReportedAsEnded() {
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.empty());
    when(repo.refusalReason(eq(saleId), anyList(), any())).thenReturn("ENDED");
    assertThatThrownBy(() -> svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(1))))
        .isInstanceOfSatisfying(FlashPricing.Refused.class, e -> assertThat(e.reason()).isEqualTo("ENDED"));
  }

  @Test
  void theCustomerLimitIsCheckedAfterTheItemIsLockedAndTheUnitsAreGivenBack() {
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(claimed(10, 3, 2, 0)));
    when(repo.userQty(itemId, user)).thenReturn(1); // 1 held + 2 asked > limit 2
    assertThatThrownBy(() -> svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(2))))
        .isInstanceOfSatisfying(FlashPricing.Refused.class, e -> assertThat(e.reason()).isEqualTo("CUSTOMER_LIMIT"));
    verify(repo).unclaim(itemId, 2);
  }

  @Test
  void theSaleLevelCustomerLimitAppliesWhenTheItemHasNoneButNeverToWalkIns() {
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(claimed(null, 3, null, 2)));
    when(repo.userQty(itemId, user)).thenReturn(1);
    assertThatThrownBy(() -> svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(2)))).isInstanceOf(FlashPricing.Refused.class);
    svc.consume(Channel.POS, UUID.randomUUID(), null, List.of(claim(2))); // no customer: nothing to limit
    verify(repo).insertConsumption(any(), any(), any(), eq("POS"), any(), any(), any(), eq(2), ArgumentMatchers.anyLong(),
        ArgumentMatchers.anyLong(), any());
  }

  @Test
  void lenientConsumeSkipsWhatDoesNotFit() {
    UUID other = UUID.randomUUID();
    when(repo.claim(eq(saleId), eq(product), anyInt(), any(), any())).thenReturn(Optional.empty());
    when(repo.claim(eq(saleId), eq(other), anyInt(), any(), any())).thenReturn(Optional.of(claimed(null, 1, null, 0)));
    when(repo.refusalReason(any(), anyList(), any())).thenReturn("LIMIT_REACHED");
    svc.consumeLenient(Channel.POS, UUID.randomUUID(), null, List.of(claim(1), new Claim(saleId, other, 1, 8000, 10000)));
    verify(repo).insertConsumption(any(), any(), eq(other), any(), any(), any(), any(), eq(1), ArgumentMatchers.anyLong(),
        ArgumentMatchers.anyLong(), any());
  }

  @Test
  void sellingTheLastUnitRaisesTheSoldOutAlertOnce() {
    OutboxService outbox = mock(OutboxService.class);
    svc.setOutbox(outbox);
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(claimed(5, 5, null, 0)));
    when(repo.markSoldOutNotified(itemId, NOW)).thenReturn(true, false);
    svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(1)));
    svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(1)));
    verify(outbox, Mockito.times(1)).add(eq("FLASH_ITEM_SOLD_OUT"), any());
  }

  @Test
  void aCapThatIsNotYetReachedRaisesNoAlert() {
    OutboxService outbox = mock(OutboxService.class);
    svc.setOutbox(outbox);
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(claimed(5, 4, null, 0)));
    svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(1)));
    verify(outbox, never()).add(any(), any());
    verify(repo, never()).markSoldOutNotified(any(), any());
  }

  @Test
  void soldOutWithoutAnOutboxStillWorks() {
    when(repo.claim(any(), any(), anyInt(), any(), any())).thenReturn(Optional.of(claimed(5, 5, null, 0)));
    when(repo.markSoldOutNotified(itemId, NOW)).thenReturn(true);
    svc.consume(Channel.ONLINE, UUID.randomUUID(), user, List.of(claim(1)));
  }

  @Test
  void releaseGivesBackOnceThroughTheRepository() {
    UUID order = UUID.randomUUID();
    when(repo.release(order, NOW)).thenReturn(List.of(new FlashRepository.Released(itemId, saleId, product, 2, "ONLINE")), List.of());
    svc.release(order);
    svc.release(order);
    verify(repo, Mockito.times(2)).release(order, NOW);
  }
}
