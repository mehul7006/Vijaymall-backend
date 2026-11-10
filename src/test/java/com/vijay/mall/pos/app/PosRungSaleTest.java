package com.vijay.mall.pos.app;

import com.vijay.mall.common.tax.GstRates;
import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.Item;
import com.vijay.mall.pos.app.PosViews.ManualDiscount;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.Rung;
import com.vijay.mall.pos.app.PosViews.RungLine;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.common.tax.GstCalculator;
import com.vijay.mall.pos.domain.OfflineExceptionKind;
import com.vijay.mall.pos.domain.OfflineFinding;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.common.tax.TaxSplit;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosHoldRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** An offline bill that carries the till's own numbers (rung) is recorded as rung; differences become exceptions. */
class PosRungSaleTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private final UUID atta = UUID.randomUUID();
  private final UUID old = UUID.randomUUID();
  private PosCatalogService catalog;
  private PosSaleRepository sales;
  private InventoryService inventory;
  private UserRepository users;
  private AuditService audit;
  private InvoiceCounterRepository counters;
  private RuntimeSettings settings;
  private OfflineExceptionService exceptions;
  private ShopService shop;
  private PosService service;

  private PosService newService(Instant now) {
    PosService s = new PosService(catalog, mock(QuoteService.class), inventory, mock(WalletService.class), mock(PromotionService.class), users,
        shop, sales, mock(PosHoldRepository.class), counters, audit, JsonMapper.builder().build(),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(now, ZoneOffset.UTC), mock(CreditService.class));
    s.setOfflineExceptions(exceptions);
    return s;
  }

  @BeforeEach
  void setUp() {
    catalog = mock(PosCatalogService.class);
    sales = mock(PosSaleRepository.class);
    inventory = mock(InventoryService.class);
    when(inventory.takeWhatIsLeftCosted(anyList(), any(), anyString())).thenReturn(new InventoryService.Taken(List.of(), Map.of()));
    users = mock(UserRepository.class);
    audit = mock(AuditService.class);
    counters = mock(InvoiceCounterRepository.class);
    settings = mock(RuntimeSettings.class);
    exceptions = mock(OfflineExceptionService.class);
    shop = mock(ShopService.class);
    service = newService(NOW);
    when(shop.get()).thenReturn(PosTestData.shop());
    when(catalog.productsById(any())).thenReturn(Map.of(rice, PosTestData.product(rice, "Rice", 5900, true),
        atta, PosTestData.product(atta, "Loose Atta", 4000, true), old, PosTestData.product(old, "Old Soap", 1000, false)));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, GstRates.of(5), "1006"),
        atta, new ProductPos(atta, SoldBy.WEIGHT, "12", GstRates.of(0), "1101"), old, new ProductPos(old, SoldBy.UNIT, null, GstRates.of(18), "3401")));
    when(counters.next(anyString(), anyString())).thenReturn(42L);
    when(sales.findByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
  }

  private RungLine line(UUID p, Integer qty, Long price, Integer gst, Long discount) { return new RungLine(p, qty, price, gst == null ? null : GstRates.of(gst), discount, null, null); }

  private static RungLine withGst(RungLine l, String rate) {
    return new RungLine(l.productId(), l.qty(), l.unitPricePaise(), new java.math.BigDecimal(rate), l.discountPaise(), l.name(), l.linePaise());
  }

  private RungLine rice(int qty, long price) { return line(rice, qty, price, 5, null); }

  private static Rung rung(RungLine... lines) { return new Rung(List.of(lines)); }

  private static Cart cart() { return new Cart(null, null, null, null, null); }

  private SaleRecord ring(RungLine l, long pay) { return ring(rung(l), pay); }

  private SaleRecord ring(Rung r, long pay) { return ring(cart(), r, List.of(new Pay("CASH", pay, null)), null, null, null); }

  private SaleRecord ring(Cart c, Rung r, List<Pay> pays, Long tendered, ManualDiscount md, Instant soldAt) {
    return service.sale(cashier, false, "k-" + UUID.randomUUID(), new SaleInput(c, pays, tendered, md, soldAt, true, r));
  }

  private SaleRecord stored() {
    ArgumentCaptor<SaleRecord> c = ArgumentCaptor.forClass(SaleRecord.class);
    verify(sales).insert(c.capture(), anyString());
    return c.getValue();
  }

  @SuppressWarnings("unchecked")
  private List<OfflineFinding> flagged() {
    ArgumentCaptor<List<OfflineFinding>> c = ArgumentCaptor.forClass(List.class);
    verify(exceptions).record(any(), c.capture());
    return c.getValue();
  }

  private void refused(Runnable r, String fragment) {
    assertThatThrownBy(r::run).isInstanceOf(ApiException.class).hasMessageContaining(fragment);
    verify(sales, never()).insert(any(), anyString());
  }

  // ---------- the happy paths ----------
  @Test
  void aBillAtOldPricesIsRecordedAtThosePricesWithTheTaxWorkedOutFromThem() {
    ring(rung(rice(2, 5000)), 10000); // the catalog says 59.00 now
    SaleRecord s = stored();
    TaxSplit tax = GstCalculator.split(10000, GstRates.of(5));
    assertThat(s.totalPaise()).isEqualTo(10000);
    assertThat(s.items().get(0).unitPricePaise()).isEqualTo(5000);
    assertThat(s.items().get(0).gstPercent()).isEqualByComparingTo("5");
    assertThat(s.items().get(0).hsn()).isEqualTo("1006");
    assertThat(s.taxablePaise()).isEqualTo(tax.taxable());
    assertThat(s.cgstPaise()).isEqualTo(tax.cgst());
    assertThat(s.sgstPaise()).isEqualTo(tax.sgst());
    assertThat(s.subtotalPaise()).isEqualTo(10000);
    assertThat(s.coinsEarned()).isZero();
    assertThat(s.createdAt()).as("no soldAt given: the upload time").isEqualTo(NOW);
    assertThat(flagged()).extracting(OfflineFinding::kind).containsExactly(OfflineExceptionKind.PRICE_CHANGED);
    OfflineFinding f = flagged().get(0);
    assertThat(f.productId()).isEqualTo(rice);
    assertThat(f.expected()).isEqualTo("5900");
    assertThat(f.actual()).isEqualTo("5000");
    verify(inventory).takeWhatIsLeftCosted(anyList(), eq(s.id()), eq("POS_SALE"));
    verify(audit).log(eq(cashier), eq("SALE"), eq("pos_sale"), eq(s.id()), anyString());
  }

  @Test
  void aBillThatMatchesTheCatalogHasNoExceptions() {
    ring(rung(rice(1, 5900)), 5900);
    verify(exceptions, never()).record(any(), anyList());
    assertThat(stored().taxablePaise()).isEqualTo(GstCalculator.split(5900, GstRates.of(5)).taxable());
  }

  @Test
  void aChangedGstRateIsKeptAsRungAndFlagged() {
    ring(line(rice, 1, 5900L, 12, null), 5900);
    assertThat(stored().items().get(0).gstPercent()).isEqualByComparingTo("12");
    OfflineFinding f = flagged().get(0);
    assertThat(f.kind()).isEqualTo(OfflineExceptionKind.GST_CHANGED);
    assertThat(f.expected()).isEqualTo("5");
    assertThat(f.actual()).isEqualTo("12");
  }

  @Test
  void aProductSwitchedOffSinceIsStillSoldAndFlagged() {
    ring(line(old, 1, 1000L, 18, null), 1000);
    assertThat(flagged()).extracting(OfflineFinding::kind).containsExactly(OfflineExceptionKind.PRODUCT_INACTIVE);
    assertThat(stored().totalPaise()).isEqualTo(1000);
  }

  @Test
  void aLineDiscountIsKeptAndFlagged() {
    ring(line(rice, 2, 5900L, 5, 800L), 11000);
    SaleRecord s = stored();
    assertThat(s.items().get(0).linePaise()).isEqualTo(11800);
    assertThat(s.items().get(0).discountPaise()).isEqualTo(800);
    assertThat(s.items().get(0).netPaise()).isEqualTo(11000);
    assertThat(s.subtotalPaise()).as("the receipt adds up: lines after their own discount").isEqualTo(11000);
    assertThat(s.taxablePaise()).isEqualTo(GstCalculator.split(11000, GstRates.of(5)).taxable());
    OfflineFinding f = flagged().get(0);
    assertThat(f.kind()).isEqualTo(OfflineExceptionKind.DISCOUNT);
    assertThat(f.actual()).isEqualTo("800");
  }

  @Test
  void aManualDiscountIsSpreadOverTheLinesUnapprovedAndFlagged() {
    ring(cart(), rung(rice(2, 5900)), List.of(new Pay("CASH", 11000, null)), null, new ManualDiscount(800, " festival ", "1234"), null);
    SaleRecord s = stored();
    assertThat(s.manualDiscountPaise()).isEqualTo(800);
    assertThat(s.manualDiscountReason()).isEqualTo("festival");
    assertThat(s.manualDiscountApprover()).as("no PIN could be checked offline").isNull();
    assertThat(s.totalPaise()).isEqualTo(11000);
    assertThat(flagged().get(0).kind()).isEqualTo(OfflineExceptionKind.DISCOUNT);
    assertThat(flagged().get(0).productId()).isNull();
    verify(audit).log(eq(cashier), eq("MANUAL_DISCOUNT"), eq("pos_sale"), eq(s.id()), anyString());
  }

  @Test
  void aManualDiscountWithoutAReasonOrAmountIsHandled() {
    ring(cart(), rung(rice(1, 5900)), List.of(new Pay("CASH", 5800, null)), null, new ManualDiscount(100, null, null), null);
    assertThat(stored().manualDiscountReason()).isEmpty();
    assertThat(flagged().get(0).detail()).contains("none given");
  }

  @Test
  void aZeroManualDiscountIsNothing() {
    ring(cart(), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, new ManualDiscount(0, "x", null), null);
    verify(exceptions, never()).record(any(), anyList());
  }

  @Test
  void aWeighedItemKeepsItsGramSemantics() {
    // 500 g at Rs 40.00 per kg = Rs 20.00
    RungLine l = new RungLine(atta, 500, 4000L, GstRates.of(0), null, "Atta loose", 2000L);
    ring(rung(l), 2000);
    SaleRecord s = stored();
    assertThat(s.items().get(0).soldBy()).isEqualTo(SoldBy.WEIGHT);
    assertThat(s.items().get(0).qty()).isEqualTo(500);
    assertThat(s.items().get(0).name()).isEqualTo("Atta loose");
    assertThat(s.totalPaise()).isEqualTo(2000);
    verify(exceptions, never()).record(any(), anyList());
  }

  @Test
  void aBlankTillNameFallsBackToTheCatalogName() {
    ring(new Rung(List.of(new RungLine(rice, 1, 5900L, GstRates.of(5), 0L, "  ", null))), 5900);
    assertThat(stored().items().get(0).name()).isEqualTo("Rice");
  }

  @Test
  void stockThatRanShortBecomesAnOversoldExceptionAndTheBillIsStillRecorded() {
    when(inventory.takeWhatIsLeftCosted(anyList(), any(), anyString())).thenReturn(new InventoryService.Taken(List.of(Map.of("productId", rice.toString(), "sold", 3, "taken", 1)), Map.of()));
    SaleRecord out = ring(rung(rice(3, 5900)), 17700);
    assertThat(out).isNotNull();
    OfflineFinding f = flagged().get(0);
    assertThat(f.kind()).isEqualTo(OfflineExceptionKind.OVERSOLD);
    assertThat(f.productId()).isEqualTo(rice);
    assertThat(f.expected()).isEqualTo("1");
    assertThat(f.actual()).isEqualTo("3");
    verify(audit).log(eq(cashier), eq("OFFLINE_OVERSOLD"), eq("pos_sale"), any(), anyString());
  }

  @Test
  void severalDifferencesOnOneBillAreAllKept() {
    ring(line(old, 1, 900L, 12, 100L), 800);
    assertThat(flagged()).extracting(OfflineFinding::kind).containsExactly(OfflineExceptionKind.PRODUCT_INACTIVE,
        OfflineExceptionKind.PRICE_CHANGED, OfflineExceptionKind.GST_CHANGED, OfflineExceptionKind.DISCOUNT);
  }

  @Test
  void withoutTheExceptionServiceAFlaggedBillCannotBeRecorded() {
    service.setOfflineExceptions(null);
    assertThatThrownBy(() -> ring(rung(rice(1, 5000)), 5000)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theRoundOffSettingAppliesToTheTillsOwnTotal() {
    service.setSettings(settings);
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NEAREST_RUPEE");
    ring(rung(rice(2, 5015)), 10000); // 100.30 rounds down to 100.00
    SaleRecord s = stored();
    assertThat(s.totalPaise()).isEqualTo(10000);
    assertThat(s.roundOffPaise()).isEqualTo(-30);
  }

  @Test
  void withRoundOffOnThePaymentMustMatchTheRoundedAmount() {
    service.setSettings(settings);
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NEAREST_RUPEE");
    refused(() -> ring(rung(rice(2, 5015)), 10030), "payments add up");
  }

  @Test
  void cashTenderedGivesTheChangeAndAWrongTenderIsRefused() {
    ring(cart(), rung(rice(1, 5000)), List.of(new Pay("CASH", 5000, null)), 10000L, null, null);
    assertThat(stored().changePaise()).isEqualTo(5000);
  }

  @Test
  void aTenderLessThanTheCashPaymentIsRefused() {
    refused(() -> ring(cart(), rung(rice(1, 5000)), List.of(new Pay("CASH", 5000, null)), 100L, null, null), "tendered");
  }

  @Test
  void splitPaymentsMustAddUpToTheRungTotal() {
    ring(cart(), rung(rice(1, 5000)), List.of(new Pay("CASH", 2000, null), new Pay("UPI", 3000, "ref")), null, null, null);
    assertThat(stored().paymentType()).isEqualTo("SPLIT");
  }

  @Test
  void theCustomerMobileLinksAnExistingOrNewCounterCustomer() {
    User known = mock(User.class);
    UUID knownId = UUID.randomUUID();
    when(known.id()).thenReturn(knownId);
    when(users.findByMobile("9000000001")).thenReturn(Optional.of(known));
    ring(new Cart(null, "+91 9000000001", "  ", CoinRedemption.Mode.NONE, null), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, null);
    assertThat(stored().customerId()).isEqualTo(knownId);
    User fresh = mock(User.class);
    when(fresh.id()).thenReturn(UUID.randomUUID());
    when(users.findByMobile("9000000002")).thenReturn(Optional.empty());
    when(users.insertCounterCustomer(eq("9000000002"), any())).thenReturn(fresh);
    ring(new Cart(null, "9000000002", null, null, null), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, null);
    verify(users).insertCounterCustomer(eq("9000000002"), any());
  }

  @Test
  void aBadMobileNumberIsRefused() {
    refused(() -> ring(new Cart(null, "123", null, null, null), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, null),
        "10 digit");
  }

  @Test
  void theInvoiceYearComesFromTheSaleTimeNotTheUploadTime() {
    Instant lastMarch = Instant.parse("2026-03-30T10:00:00Z"); // uploaded on 2 April: the bill belongs to 2025-26
    service = newService(Instant.parse("2026-04-02T05:00:00Z"));
    ring(cart(), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, lastMarch);
    verify(counters).next("INVOICE", "2025-26");
    assertThat(stored().invoiceNo()).contains("2025-26");
    assertThat(stored().createdAt()).isEqualTo(lastMarch);
  }

  @Test
  void aSaleTimeOutsideTheWindowIsRefused() {
    refused(() -> ring(cart(), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, NOW.minusSeconds(10 * 86400L)), "within the last");
    refused(() -> ring(cart(), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, NOW.plusSeconds(3600)), "within the last");
  }

  @Test
  void metricsCountTheBill() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    service.setMetrics(registry);
    ring(rung(rice(1, 5900)), 5900);
    assertThat(registry.get("mall.pos.sales").tag("type", "CASH").counter().count()).isEqualTo(1);
    assertThat(registry.get("mall.pos.sales.value.paise").counter().count()).isEqualTo(5900);
  }

  // ---------- what cannot be checked offline ----------
  @Test
  void aCouponCoinsOrCreditAreRefusedOnAnAsRungBill() {
    refused(() -> ring(new Cart(null, "9000000001", "DIWALI", null, null), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, null),
        "coupon or coin");
    refused(() -> ring(new Cart(null, "9000000001", null, CoinRedemption.Mode.ALL, null), rung(rice(1, 5900)), List.of(new Pay("CASH", 5900, null)), null, null, null),
        "coupon or coin");
    refused(() -> ring(new Cart(null, "9000000001", null, null, null), rung(rice(1, 5900)), List.of(new Pay("CREDIT", 5900, null)), null, null, null),
        "credit");
  }

  // ---------- hard validation ----------
  @Test
  void aMalformedBillIsRefused() {
    refused(() -> ring(new Rung(null), 1), "no lines");
    refused(() -> ring(new Rung(List.of()), 1), "no lines");
    refused(() -> ring(new Rung(Arrays.asList((RungLine) null)), 1), "productId");
    refused(() -> ring(line(null, 1, 100L, 5, null), 100), "productId");
    refused(() -> ring(line(rice, null, 100L, 5, null), 100), "quantity");
    refused(() -> ring(line(rice, 0, 100L, 5, null), 100), "quantity");
    refused(() -> ring(line(rice, -1, 100L, 5, null), 100), "quantity");
    refused(() -> ring(line(rice, 1, null, 5, null), 100), "unitPricePaise");
    refused(() -> ring(line(rice, 1, -1L, 5, null), 100), "unitPricePaise");
    refused(() -> ring(line(rice, 1, 100L, null, null), 100), "must be one of");
    refused(() -> ring(line(rice, 1, 100L, 7, null), 100), "must be one of");
    refused(() -> ring(line(rice, 1, 100L, 5, -5L), 100), "discountPaise");
  }

  @Test
  void anUnknownProductIsRefused() {
    refused(() -> ring(line(UUID.randomUUID(), 1, 100L, 5, null), 100), "unknown product");
  }

  @Test
  void tooManyUnitsOrGramsAreRefused() {
    refused(() -> ring(rice(10_000, 1), 10_000), "too large");
    refused(() -> ring(line(atta, 100_001, 1L, 0, null), 1), "too large");
  }

  @Test
  void aLineAmountThatIsNotPriceTimesQuantityIsRefused() {
    refused(() -> ring(new Rung(List.of(new RungLine(rice, 2, 5000L, GstRates.of(5), null, null, 9999L))), 9999), "line amount");
  }

  @Test
  void discountsLargerThanTheLineOrTheBillAreRefused() {
    refused(() -> ring(line(rice, 1, 100L, 5, 101L), 0), "more than the line");
    refused(() -> ring(cart(), rung(rice(1, 5000)), List.of(new Pay("CASH", 1, null)), null, new ManualDiscount(5001, "x", null), null), "more than the bill");
    refused(() -> ring(cart(), rung(rice(1, 5000)), List.of(new Pay("CASH", 1, null)), null, new ManualDiscount(-1, "x", null), null), "negative");
  }

  @Test
  void paymentsThatDoNotAddUpToTheRungTotalAreRefused() {
    refused(() -> ring(rung(rice(2, 5000)), 9999), "payments add up");
    refused(() -> ring(cart(), rung(rice(2, 5000)), null, null, null, null), "payments add up");
    refused(() -> ring(cart(), rung(rice(2, 5000)), Arrays.asList((Pay) null), null, null, null), "empty");
    refused(() -> ring(cart(), rung(rice(2, 5000)), List.of(new Pay("BITCOIN", 10000, null)), null, null, null), "payment mode");
  }

  @Test
  void aFreeBillNeedsNoPayment() {
    ring(cart(), rung(line(rice, 1, 0L, 5, null)), List.of(), null, null, null);
    assertThat(stored().totalPaise()).isZero();
  }

  // ---------- the legacy path is untouched ----------
  @Test
  void aLegacyOfflineBillStillStoresAnOversoldExceptionToo() {
    QuoteService quotes = mock(QuoteService.class);
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    PosService legacy = new PosService(catalog, quotes, inventory, mock(WalletService.class), mock(PromotionService.class), users, shop, sales,
        mock(PosHoldRepository.class), counters, audit, JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)),
        Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
    legacy.setOfflineExceptions(exceptions);
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(new Quote(List.of(), 5900, null, 0, 0, 0, 0, 5900, 0, List.of()));
    when(inventory.takeWhatIsLeftCosted(anyList(), any(), anyString())).thenReturn(new InventoryService.Taken(List.of(Map.of("productId", rice.toString(), "sold", 1, "taken", 0)), Map.of()));
    legacy.sale(cashier, false, "legacy-1", new SaleInput(new Cart(List.of(new Item(rice, 1)), null, null, null, null),
        List.of(new Pay("CASH", 5900, null)), null, null, null, true));
    assertThat(flagged()).extracting(OfflineFinding::kind).containsExactly(OfflineExceptionKind.OVERSOLD);
    assertThat(stored().totalPaise()).isEqualTo(5900);
  }

  @Test
  void theOlderConstructorsOfTheInputHaveNoRung() {
    assertThat(new SaleInput(cart(), List.of(), null).rung()).isNull();
    assertThat(new SaleInput(cart(), List.of(), null, null).rung()).isNull();
    assertThat(new SaleInput(cart(), List.of(), null, null, null, true).rung()).isNull();
  }

  /* 5900 paise at 1.5%: 5900 * 100 / 101.5 = 5812.807, so taxable 5813 and tax 87 (CGST 43, SGST 44). */
  @Test
  void aFractionalRateOnTheBillIsKeptSplitAndFlaggedAgainstTheCatalog() {
    ring(withGst(line(rice, 1, 5900L, null, null), "1.5"), 5900);
    SaleRecord s = stored();
    assertThat(s.items().get(0).gstPercent()).isEqualByComparingTo("1.5");
    assertThat(s.taxablePaise()).isEqualTo(5813);
    assertThat(s.cgstPaise()).isEqualTo(43);
    assertThat(s.sgstPaise()).isEqualTo(44);
    OfflineFinding f = flagged().get(0);
    assertThat(f.kind()).isEqualTo(OfflineExceptionKind.GST_CHANGED);
    assertThat(f.expected()).isEqualTo("5");
    assertThat(f.actual()).isEqualTo("1.5");
  }

  @Test
  void aRateWrittenWithTrailingZerosIsTheSameRateAsTheCatalogs() {
    ring(new RungLine(old, 1, 1000L, new java.math.BigDecimal("18.00"), null, null, null), 1000);
    assertThat(flagged()).extracting(OfflineFinding::kind).containsExactly(OfflineExceptionKind.PRODUCT_INACTIVE);
  }

  @Test
  void ratesOutsideTheAllowedSetAreRefusedForTheFieldWithTheFixedText() {
    refused(() -> ring(withGst(line(rice, 1, 100L, null, null), "18.5"), 100), "must be one of 0, 0.25, 1.5, 3, 5, 12, 18, 28 or 40");
    for (String ok : new String[] {"0.25", "40"}) {
      ring(withGst(line(rice, 1, 5900L, null, null), ok), 5900);
    }
  }
}
