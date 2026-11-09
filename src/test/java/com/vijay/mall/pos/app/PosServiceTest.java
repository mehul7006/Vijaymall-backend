package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.Item;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosHoldRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class PosServiceTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private final UUID sugar = UUID.randomUUID();

  private PosCatalogService catalog;
  private QuoteService quotes;
  private InventoryService inventory;
  private WalletService wallet;
  private PromotionService promotions;
  private UserRepository users;
  private ShopService shop;
  private PosSaleRepository sales;
  private PosHoldRepository holds;
  private InvoiceCounterRepository counters;
  private CreditService credit;
  private PosService service;

  @BeforeEach
  void setUp() {
    catalog = mock(PosCatalogService.class);
    quotes = mock(QuoteService.class);
    inventory = mock(InventoryService.class);
    wallet = mock(WalletService.class);
    promotions = mock(PromotionService.class);
    users = mock(UserRepository.class);
    shop = mock(ShopService.class);
    sales = mock(PosSaleRepository.class);
    holds = mock(PosHoldRepository.class);
    counters = mock(InvoiceCounterRepository.class);
    credit = mock(CreditService.class);
    service = new PosService(catalog, quotes, inventory, wallet, promotions, users, shop, sales, holds, counters,
        mock(AuditService.class), JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)),
        Clock.fixed(NOW, ZoneOffset.UTC), credit);
    when(shop.get()).thenReturn(PosTestData.shop());
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    ProductData sugarData = PosTestData.product(sugar, "Sugar", 4500, true);
    when(catalog.productsById(any())).thenReturn(Map.of(rice, riceData, sugar, sugarData));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006"),
        sugar, new ProductPos(sugar, SoldBy.WEIGHT, "101", 5, "1701")));
    when(counters.next("INVOICE", "2026-27")).thenReturn(42L);
    when(sales.findByIdem(anyString())).thenReturn(Optional.empty());
  }

  private static Quote quote(long subtotal, String coupon, long couponDisc, int coins, long coinDisc, long earn) {
    return new Quote(List.of(), subtotal, coupon, couponDisc, coins, coinDisc, 0, subtotal - couponDisc - coinDisc, earn, List.of());
  }

  private static Cart cart(String mobile, String coupon, CoinRedemption.Mode mode, Item... items) {
    return new Cart(List.of(items), mobile, coupon, mode, null);
  }

  private SaleRecord capture() {
    ArgumentCaptor<SaleRecord> c = ArgumentCaptor.forClass(SaleRecord.class);
    verify(sales).insert(c.capture(), anyString());
    return c.getValue();
  }

  // ----- quote -----
  @Test void quote_pricesLines_andSplitsGst() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(11800, null, 0, 0, 0, 0));
    var bill = service.quote(cart(null, null, null, new Item(rice, 2)));
    assertThat(bill.subtotalPaise()).isEqualTo(11800);
    assertThat(bill.totalPaise()).isEqualTo(11800);
    assertThat(bill.taxablePaise() + bill.cgstPaise() + bill.sgstPaise()).isEqualTo(11800);
    assertThat(bill.customerCoinBalance()).isZero();
    assertThat(bill.coinsToEarn()).isZero();
  }

  @Test void quote_weighedLine_usesPricePerKg() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(2250, null, 0, 0, 0, 0));
    var bill = service.quote(cart(null, null, null, new Item(sugar, 500)));
    assertThat(bill.lines().get(0).linePaise()).isEqualTo(2250);
  }

  @Test void quote_knownCustomer_showsBalance_andEarnings() {
    UUID cust = UUID.randomUUID();
    when(users.findByMobile("9000000001")).thenReturn(Optional.of(new User(cust, "a@b.c", "A", "9000000001", "CUSTOMER")));
    when(wallet.balance(cust)).thenReturn(120);
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 5));
    var bill = service.quote(cart("+91 90000-00001", null, null, new Item(rice, 1)));
    assertThat(bill.customerCoinBalance()).isEqualTo(120);
    assertThat(bill.coinsToEarn()).isEqualTo(5);
  }

  @Test void quote_unknownCustomerWithCoupon_stillQuotes() {
    when(users.findByMobile("9000000002")).thenReturn(Optional.empty());
    when(quotes.quoteLines(any(), anyList(), eq("SAVE"), any(), any())).thenReturn(quote(5900, "SAVE", 500, 0, 0, 0));
    var bill = service.quote(cart("9000000002", "SAVE", null, new Item(rice, 1)));
    assertThat(bill.couponDiscountPaise()).isEqualTo(500);
    assertThat(bill.totalPaise()).isEqualTo(5400);
  }

  @Test void quote_couponWithoutMobile_rejected() {
    assertThatThrownBy(() -> service.quote(cart(null, "SAVE", null, new Item(rice, 1)))).isInstanceOf(ApiException.class)
        .hasMessageContaining("mobile");
  }

  @Test void quote_coinsWithoutMobile_rejected() {
    assertThatThrownBy(() -> service.quote(cart(null, null, CoinRedemption.Mode.ALL, new Item(rice, 1))))
        .isInstanceOf(ApiException.class);
  }

  @Test void quote_blankCouponAndNoneMode_allowedWithoutMobile() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    assertThat(service.quote(cart(null, " ", CoinRedemption.Mode.NONE, new Item(rice, 1))).totalPaise()).isEqualTo(5900);
  }

  @Test void quote_badMobile_rejected() {
    assertThatThrownBy(() -> service.quote(cart("12345", null, null, new Item(rice, 1)))).hasMessageContaining("10 digit");
  }

  @Test void quote_emptyBasket_rejected() {
    assertThatThrownBy(() -> service.quote(new Cart(List.of(), null, null, null, null))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.quote(new Cart(null, null, null, null, null))).isInstanceOf(ApiException.class);
  }

  @Test void quote_badQuantity_rejected() {
    assertThatThrownBy(() -> service.quote(cart(null, null, null, new Item(rice, 0)))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.quote(cart(null, null, null, new Item(null, 1)))).isInstanceOf(ApiException.class);
  }

  @Test void quote_unknownOrInactiveProduct_rejected() {
    UUID ghost = UUID.randomUUID();
    assertThatThrownBy(() -> service.quote(cart(null, null, null, new Item(ghost, 1)))).hasMessageContaining("not available");
    UUID off = UUID.randomUUID();
    when(catalog.productsById(any())).thenReturn(Map.of(off, PosTestData.product(off, "Off", 100, false)));
    when(catalog.attributesFor(any())).thenReturn(Map.of(off, ProductPos.defaults(off)));
    assertThatThrownBy(() -> service.quote(cart(null, null, null, new Item(off, 1)))).hasMessageContaining("not available");
  }

  @Test void quote_tooManyUnitsOrGrams_rejected() {
    assertThatThrownBy(() -> service.quote(cart(null, null, null, new Item(rice, 10_000)))).hasMessageContaining("too large");
    assertThatThrownBy(() -> service.quote(cart(null, null, null, new Item(sugar, 100_001)))).hasMessageContaining("too large");
  }

  @Test void quote_sameProductTwice_isMerged() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(17700, null, 0, 0, 0, 0));
    var bill = service.quote(cart(null, null, null, new Item(rice, 1), new Item(rice, 2)));
    assertThat(bill.lines()).hasSize(1);
    assertThat(bill.lines().get(0).qty()).isEqualTo(3);
  }

  // ----- sale -----
  @Test void sale_cash_noCustomer_reservesStock_andNumbersInvoice() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(11800, null, 0, 0, 0, 9));
    SaleRecord stored = mock(SaleRecord.class);
    when(sales.find(any())).thenReturn(Optional.of(stored));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 2)), List.of(new Pay("CASH", 11800, null)), 20000L);

    assertThat(service.sale(cashier, "k1", in)).isSameAs(stored);

    SaleRecord s = capture();
    assertThat(s.invoiceNo()).isEqualTo("VSM/2026-27/000042");
    assertThat(s.totalPaise()).isEqualTo(11800);
    assertThat(s.changePaise()).isEqualTo(8200);
    assertThat(s.coinsEarned()).isZero(); // no customer, nothing to credit
    assertThat(s.customerId()).isNull();
    verify(inventory).reserve(anyList(), any(UUID.class), eq("POS_SALE"));
    verify(wallet, never()).credit(any(), anyInt(), any());
    verify(users, never()).insertCounterCustomer(anyString(), any());
  }

  @Test void sale_withNewCustomer_createsCounterCustomer_usesCoinsAndCoupon_andEarns() {
    UUID cust = UUID.randomUUID();
    when(users.findByMobile("9000000003")).thenReturn(Optional.empty());
    when(users.insertCounterCustomer(eq("9000000003"), any())).thenReturn(new User(cust, "pos@x", null, "9000000003", "CUSTOMER"));
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(11800, "SAVE", 500, 10, 1000, 7));
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    var in = new SaleInput(cart("9000000003", "SAVE", CoinRedemption.Mode.ALL, new Item(rice, 2)),
        List.of(new Pay("UPI", 6000, "u1"), new Pay("CASH", 4300, null)), null);

    service.sale(cashier, "k2", in);

    SaleRecord s = capture();
    assertThat(s.totalPaise()).isEqualTo(10300);
    assertThat(s.coinsEarned()).isEqualTo(7);
    assertThat(s.coinsUsed()).isEqualTo(10);
    verify(wallet).redeem(eq(cust), eq(10), any(UUID.class));
    verify(promotions).consume(eq("SAVE"), eq(cust), any(UUID.class), eq(500L));
    verify(wallet).credit(eq(cust), eq(7), any(UUID.class));
  }

  @Test void sale_existingCustomer_isNotRecreated() {
    UUID cust = UUID.randomUUID();
    when(users.findByMobile("9000000004")).thenReturn(Optional.of(new User(cust, "a@b.c", "A", "9000000004", "CUSTOMER")));
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    service.sale(cashier, "k3", new SaleInput(cart("9000000004", null, null, new Item(rice, 1)), List.of(new Pay("CARD", 5900, "appr")), null));
    verify(users, never()).insertCounterCustomer(anyString(), any());
  }

  @Test void sale_paymentsNotAddingUp_rejected_andNothingReserved() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(11800, null, 0, 0, 0, 0));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 2)), List.of(new Pay("CASH", 100, null)), null);
    assertThatThrownBy(() -> service.sale(cashier, "k4", in)).isInstanceOf(ApiException.class).hasMessageContaining("add up");
    verify(inventory, never()).reserve(anyList(), any(), anyString());
  }

  @Test void sale_nullPayments_treatedAsNone() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 1)), null, null);
    assertThatThrownBy(() -> service.sale(cashier, "k5", in)).isInstanceOf(ApiException.class);
  }

  @Test void sale_missingIdempotencyKey_rejected() {
    var in = new SaleInput(cart(null, null, null, new Item(rice, 1)), List.of(), null);
    assertThatThrownBy(() -> service.sale(cashier, " ", in)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.sale(cashier, null, in)).isInstanceOf(ApiException.class);
  }

  @Test void sale_repeatedKey_returnsFirstBill_withoutWork() {
    SaleRecord first = mock(SaleRecord.class);
    when(sales.findByIdem("dup")).thenReturn(Optional.of(first));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 1)), List.of(), null);
    assertThat(service.sale(cashier, "dup", in)).isSameAs(first);
    verify(inventory, never()).reserve(anyList(), any(), anyString());
  }

  @Test void sale_raceOnKey_returnsTheWinner() {
    SaleRecord winner = mock(SaleRecord.class);
    when(sales.findByIdem("race")).thenReturn(Optional.empty(), Optional.of(winner));
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    Mockito.doThrow(new DataIntegrityViolationException("dup")).when(sales).insert(any(), eq("race"));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 1)), List.of(new Pay("CASH", 5900, null)), null);
    assertThat(service.sale(cashier, "race", in)).isSameAs(winner);
  }

  @Test void sale_raceWithoutWinner_rethrows() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    Mockito.doThrow(new DataIntegrityViolationException("dup")).when(sales).insert(any(), eq("lost"));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 1)), List.of(new Pay("CASH", 5900, null)), null);
    assertThatThrownBy(() -> service.sale(cashier, "lost", in)).isInstanceOf(DataIntegrityViolationException.class);
  }

  // ----- reading -----
  @Test void get_missing_isNotFound() {
    when(sales.find(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ApiException.class);
  }

  @Test void search_byInvoice_mobile_andDay() {
    SaleRecord s = mock(SaleRecord.class);
    when(sales.findByInvoiceNo("VSM/2026-27/000001")).thenReturn(Optional.of(s));
    assertThat(service.search(" VSM/2026-27/000001 ", null, null)).containsExactly(s);

    when(sales.byMobile("9000000001")).thenReturn(List.of(s));
    assertThat(service.search(null, "9000000001", null)).containsExactly(s);

    when(sales.between(any(), any())).thenReturn(List.of(s, s));
    assertThat(service.search(" ", " ", LocalDate.of(2026, 10, 27))).hasSize(2);
    assertThat(service.search(null, null, null)).hasSize(2); // today
    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    verify(sales, Mockito.times(2)).between(from.capture(), any());
    assertThat(from.getAllValues().get(0)).isEqualTo(Instant.parse("2026-10-26T18:30:00Z")); // midnight IST
  }

  @Test void upiLink_built_orRefused() {
    assertThat(service.upiLink(12345, "bill 1")).startsWith("upi://pay?pa=shop%40upi").contains("am=123.45");
    assertThatThrownBy(() -> service.upiLink(0, null)).isInstanceOf(ApiException.class);
    when(shop.get()).thenReturn(new PosRecords.ShopSetting("S", "", "", "", "24", "", "VSM", "", "2",
        WeighedMode.WEIGHT_GRAMS));
    assertThatThrownBy(() -> service.upiLink(100, null)).hasMessageContaining("UPI");
  }

  @Test void receipt_rendersBill_forGivenWidth() {
    UUID id = UUID.randomUUID();
    SaleRecord s = PosTestData.sale(id, UUID.randomUUID(), List.of(PosTestData.item(UUID.randomUUID(), rice, 2, 5000, 10000, 5, 0)), 3, 0);
    when(sales.find(id)).thenReturn(Optional.of(s));
    when(users.findById(s.cashierId())).thenReturn(Optional.of(new User(s.cashierId(), "c@x", "Asha", null, "CASHIER")));
    String text = service.receipt(id, 32);
    assertThat(text).contains("TAX INVOICE", "VSM/2026-27/000001", "Asha", "27-10-2026 10:30", "Coins earned: 3");
    assertThatThrownBy(() -> service.receipt(id, 40)).isInstanceOf(ApiException.class);
  }

  @Test void receipt_cashierWithoutName_fallsBackToEmail_orBlank() {
    UUID id = UUID.randomUUID();
    SaleRecord s = PosTestData.sale(id, null, List.of(PosTestData.item(UUID.randomUUID(), rice, 1, 5000, 5000, 0, 0)), 0, 0);
    when(sales.find(id)).thenReturn(Optional.of(s));
    when(users.findById(s.cashierId())).thenReturn(Optional.of(new User(s.cashierId(), "c@x", " ", null, "CASHIER")));
    assertThat(service.receipt(id, 48)).contains("c@x");
    when(users.findById(s.cashierId())).thenReturn(Optional.empty());
    assertThat(service.receipt(id, 48)).contains("Cashier:");
  }

  // ----- holds -----
  @Test void hold_savesBasket_andReadsItBack() {
    UUID hid = UUID.randomUUID();
    ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
    when(holds.insert(eq(cashier), eq("table 2"), eq("9000000001"), payload.capture(), any())).thenAnswer(inv ->
        new HoldRecord(hid, cashier, "table 2", "9000000001", payload.getValue(), NOW));
    var view = service.hold(cashier, " table 2 ", cart("9000000001", null, null, new Item(rice, 3)));
    assertThat(view.cart().items()).hasSize(1);
    assertThat(view.cart().items().get(0).qty()).isEqualTo(3);
    assertThat(view.id()).isEqualTo(hid);
  }

  @Test void hold_emptyBasket_rejected_andNullLabelAllowed() {
    assertThatThrownBy(() -> service.hold(cashier, "x", new Cart(List.of(), null, null, null, null))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.hold(cashier, "x", new Cart(null, null, null, null, null))).isInstanceOf(ApiException.class);
    when(holds.insert(any(), eq(""), any(), anyString(), any())).thenReturn(
        new HoldRecord(UUID.randomUUID(), cashier, "", null, "{\"items\":[{\"productId\":\"" + rice + "\",\"qty\":1}]}", NOW));
    assertThat(service.hold(cashier, null, cart(null, null, null, new Item(rice, 1))).label()).isEmpty();
  }

  @Test void holds_listGetDiscard() {
    HoldRecord r = new HoldRecord(UUID.randomUUID(), cashier, "a", null, "{\"items\":[]}", NOW);
    when(holds.all()).thenReturn(List.of(r));
    when(holds.find(r.id())).thenReturn(Optional.of(r));
    assertThat(service.holds()).hasSize(1);
    assertThat(service.getHold(r.id()).label()).isEqualTo("a");
    when(holds.delete(r.id())).thenReturn(true);
    service.discardHold(r.id());
    UUID gone = UUID.randomUUID();
    when(holds.find(gone)).thenReturn(Optional.empty());
    when(holds.delete(gone)).thenReturn(false);
    assertThatThrownBy(() -> service.getHold(gone)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.discardHold(gone)).isInstanceOf(ApiException.class);
  }

  @Test void getHold_corruptPayload_isServerError() {
    HoldRecord bad = new HoldRecord(UUID.randomUUID(), cashier, "a", null, "not json", NOW);
    when(holds.find(bad.id())).thenReturn(Optional.of(bad));
    assertThatThrownBy(() -> service.getHold(bad.id())).isInstanceOf(IllegalStateException.class);
  }

  // ---------- credit and hybrid ----------
  private UUID creditCustomer(String mobile) {
    UUID cust = UUID.randomUUID();
    when(users.findByMobile(mobile)).thenReturn(Optional.of(new User(cust, "a@b.c", "A", mobile, "CUSTOMER")));
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    return cust;
  }

  @Test void sale_fullyOnCredit_putsTheWholeBillOnTheCustomersAccount() {
    UUID cust = creditCustomer("9000000005");
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(11800, null, 0, 0, 0, 0));
    service.sale(cashier, "c1", new SaleInput(cart("9000000005", null, null, new Item(rice, 2)), List.of(new Pay("CREDIT", 11800, null)), null));
    SaleRecord s = capture();
    assertThat(s.paymentType()).isEqualTo("CREDIT");
    assertThat(s.creditPaise()).isEqualTo(11800);
    verify(credit).chargeSale(eq(cust), eq(11800L), any(UUID.class), eq("VSM/2026-27/000042"), eq(cashier), any());
  }

  @Test void sale_hybrid_putsOnlyTheCreditShareOnTheAccount() {
    UUID cust = creditCustomer("9000000006");
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(11800, null, 0, 0, 0, 0));
    service.sale(cashier, "c2", new SaleInput(cart("9000000006", null, null, new Item(rice, 2)),
        List.of(new Pay("CASH", 5000, null), new Pay("UPI", 1800, "u"), new Pay("CREDIT", 5000, null)), null));
    assertThat(capture().paymentType()).isEqualTo("HYBRID");
    verify(credit).chargeSale(eq(cust), eq(5000L), any(UUID.class), anyString(), eq(cashier), any());
  }

  @Test void sale_onCredit_needsACustomer_andReservesNothing() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    var in = new SaleInput(cart(null, null, null, new Item(rice, 1)), List.of(new Pay("CREDIT", 5900, null)), null);
    assertThatThrownBy(() -> service.sale(cashier, "c3", in)).isInstanceOf(ApiException.class).hasMessageContaining("mobile");
    verify(inventory, never()).reserve(anyList(), any(), anyString());
    verify(credit, never()).chargeSale(any(), anyLong(), any(), anyString(), any(), any());
  }

  @Test void sale_creditRefusedByTheAccount_failsTheBill() {
    UUID cust = creditCustomer("9000000007");
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    Mockito.doThrow(ApiException.unprocessable("CREDIT_LIMIT_EXCEEDED", "over", Map.of()))
        .when(credit).chargeSale(any(), anyLong(), any(), anyString(), any(), any());
    var in = new SaleInput(cart("9000000007", null, null, new Item(rice, 1)), List.of(new Pay("CREDIT", 5900, null)), null);
    assertThatThrownBy(() -> service.sale(cashier, "c4", in)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("CREDIT_LIMIT_EXCEEDED"));
  }

  @Test void sale_twoCreditLines_rejected() {
    UUID cust = creditCustomer("9000000008");
    when(quotes.quoteLines(eq(cust), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    var in = new SaleInput(cart("9000000008", null, null, new Item(rice, 1)),
        List.of(new Pay("CREDIT", 2900, null), new Pay("CREDIT", 3000, null)), null);
    assertThatThrownBy(() -> service.sale(cashier, "c5", in)).isInstanceOf(ApiException.class).hasMessageContaining("only one credit");
  }

  @Test void sale_withoutCredit_neverTouchesTheCreditAccount() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quote(5900, null, 0, 0, 0, 0));
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    service.sale(cashier, "c6", new SaleInput(cart(null, null, null, new Item(rice, 1)), List.of(new Pay("CASH", 5900, null)), null));
    assertThat(capture().paymentType()).isEqualTo("CASH");
    verify(credit, never()).chargeSale(any(), anyLong(), any(), anyString(), any(), any());
  }
}
