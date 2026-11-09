package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.PosRecords;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosHoldRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Branches of PosService the main PosServiceTest leaves out. */
class PosServiceCoverageTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();

  private QuoteService quotes;
  private UserRepository users;
  private ShopService shop;
  private PosSaleRepository sales;
  private PosHoldRepository holds;
  private InvoiceCounterRepository counters;
  private InventoryService inventory;
  private JsonMapper json = JsonMapper.builder().build();
  private PosService service;

  @BeforeEach
  void setUp() {
    PosCatalogService catalog = mock(PosCatalogService.class);
    quotes = mock(QuoteService.class);
    inventory = mock(InventoryService.class);
    users = mock(UserRepository.class);
    shop = mock(ShopService.class);
    sales = mock(PosSaleRepository.class);
    holds = mock(PosHoldRepository.class);
    counters = mock(InvoiceCounterRepository.class);
    build(catalog);
    when(shop.get()).thenReturn(PosTestData.shop());
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    when(catalog.productsById(any())).thenReturn(Map.of(rice, riceData));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006")));
    when(counters.next("INVOICE", "2026-27")).thenReturn(42L);
    when(sales.findByIdem(anyString())).thenReturn(Optional.empty());
  }

  private PosCatalogService catalogMock;

  private void build(PosCatalogService catalog) {
    catalogMock = catalog;
    service = new PosService(catalog, quotes, inventory, mock(WalletService.class), mock(PromotionService.class), users, shop,
        sales, holds, counters, mock(AuditService.class), json,
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
  }

  private static Cart cart(String mobile) {
    return new Cart(List.of(new Item(UUID.randomUUID(), 1)), mobile, null, null, null);
  }

  private Cart riceCart() { return new Cart(List.of(new Item(rice, 2)), null, null, null, null); }

  private Quote quoteWithLine(long lineCoupon, long quoteCoupon, long coin) {
    var line = new Quote.Line(rice, "Rice", 2, 5900, 11800, true, true, "", "", null, lineCoupon);
    return new Quote(List.of(line), 11800, quoteCoupon > 0 ? "SAVE" : null, quoteCoupon, 0, coin, 0, 11800 - quoteCoupon - coin, 0,
        List.of());
  }

  @Test void quote_perLineCouponShareThatMatchesTheCoupon_isAppliedToThatLine() {
    when(users.findByMobile("9000000001")).thenReturn(Optional.of(new User(UUID.randomUUID(), "a@b.c", "A", "9000000001", "CUSTOMER")));
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quoteWithLine(500, 500, 300));
    Cart c = new Cart(List.of(new Item(rice, 2)), "9000000001", "SAVE", null, null);
    var bill = service.quote(c);
    assertThat(bill.lines().get(0).discountPaise()).isEqualTo(800);
    assertThat(bill.totalPaise()).isEqualTo(11000);
  }

  @Test void quote_lineSharesThatDoNotAddUpToTheCoupon_fallBackToSpreadingTheWholeDiscount() {
    when(users.findByMobile("9000000001")).thenReturn(Optional.of(new User(UUID.randomUUID(), "a@b.c", "A", "9000000001", "CUSTOMER")));
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(quoteWithLine(100, 500, 300));
    Cart c = new Cart(List.of(new Item(rice, 2)), "9000000001", "SAVE", null, null);
    var bill = service.quote(c);
    assertThat(bill.lines().get(0).discountPaise()).isEqualTo(800);
    assertThat(bill.totalPaise()).isEqualTo(11000);
  }

  @Test void quote_blankMobileIsTreatedAsNoCustomer() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(new Quote(List.of(), 11800, null, 0, 0, 0, 0, 11800, 0, List.of()));
    var bill = service.quote(new Cart(List.of(new Item(rice, 2)), "   ", null, null, null));
    assertThat(bill.totalPaise()).isEqualTo(11800);
    verify(users, never()).findByMobile(anyString());
  }

  @Test void hold_blankMobileIsAllowedAndStoredAsNull() {
    when(holds.insert(any(), anyString(), any(), anyString(), any())).thenReturn(
        new PosRecords.HoldRecord(UUID.randomUUID(), cashier, "L", null, "{}", NOW));
    service.hold(cashier, " L ", new Cart(List.of(new Item(rice, 1)), "  ", null, null, null));
    verify(holds).insert(ArgumentMatchers.eq(cashier), ArgumentMatchers.eq("L"),
        ArgumentMatchers.isNull(), anyString(), any());
  }

  @Test void hold_refusesTooManyLinesAndAFullTill() {
    var lines = new java.util.ArrayList<Item>();
    for (int i = 0; i < PosService.MAX_HOLD_LINES + 1; i++) lines.add(new Item(UUID.randomUUID(), 1));
    assertThatThrownBy(() -> service.hold(cashier, "L", new Cart(lines, null, null, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details()).isEqualTo(Map.of("fields", Map.of("items", "must have at most 50 lines"))));

    when(holds.count()).thenReturn(100L);
    assertThatThrownBy(() -> service.hold(cashier, "L", cart(null)))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status().value()).isEqualTo(409);
          assertThat(e.code()).isEqualTo("HOLD_LIMIT");
          assertThat(e.details()).isEqualTo(Map.of("max", 100));
        });
    verify(holds, never()).insert(any(), anyString(), any(), anyString(), any());
  }

  @Test void hold_quantityBoundsFollowHowTheProductIsSold() {
    UUID loose = UUID.randomUUID();
    when(catalogMock.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006"),
        loose, new ProductPos(loose, SoldBy.WEIGHT, "101", 5, "1701")));
    when(holds.insert(any(), anyString(), any(), anyString(), any())).thenReturn(new PosRecords.HoldRecord(UUID.randomUUID(), cashier, "", null, "{}", NOW));
    service.hold(cashier, "", new Cart(List.of(new Item(rice, 9999), new Item(loose, 25000)), null, null, null, null));
    for (var bad : List.of(new Item(rice, 0), new Item(rice, 10000), new Item(loose, 100001), new Item(UUID.randomUUID(), -3))) {
      assertThatThrownBy(() -> service.hold(cashier, "", new Cart(List.of(new Item(rice, 1), bad), null, null, null, null)))
          .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.details().toString()).contains("cart.items[1].qty=must be 1 to "));
    }
  }

  @Test void hold_basketThatCannotBeWritten_isAServerError() {
    JsonMapper broken = mock(JsonMapper.class);
    when(broken.writeValueAsString(any())).thenThrow(new JacksonException("boom") { });
    json = broken;
    build(catalogMock);
    assertThatThrownBy(() -> service.hold(cashier, "L", cart(null)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not store held basket");
    verify(holds, never()).insert(any(), anyString(), any(), anyString(), any());
  }

  @Test void sale_whenTheShopRequiresAShift_theCashierMustHaveOneOpen() {
    ShiftService shifts = mock(ShiftService.class);
    service.setShiftRule(shifts, true);
    doThrow(ApiException.unprocessable("NO_OPEN_SHIFT", "open a shift", Map.of())).when(shifts).requireOpen(cashier);
    var in = new SaleInput(riceCart(), List.of(), null);
    assertThatThrownBy(() -> service.sale(cashier, "k-shift", in)).isInstanceOf(ApiException.class);
    verify(shifts).requireOpen(cashier);
    verify(sales, never()).insert(any(), anyString());
  }

  @Test void sale_whenTheCashierHasAShiftOpen_billingGoesAhead() {
    ShiftService shifts = mock(ShiftService.class);
    service.setShiftRule(shifts, true);
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(new Quote(List.of(), 11800, null, 0, 0, 0, 0, 11800, 0, List.of()));
    SaleRecord stored = mock(SaleRecord.class);
    when(sales.find(any())).thenReturn(Optional.of(stored));
    var in = new SaleInput(riceCart(), List.of(new Pay("CASH", 11800, null)), null);
    assertThat(service.sale(cashier, "k-open", in)).isSameAs(stored);
    verify(shifts).requireOpen(cashier);
    verify(sales).insert(any(), anyString());
  }

  @Test void sale_shiftRuleOff_orNoShiftService_neverChecksShifts() {
    ShiftService shifts = mock(ShiftService.class);
    service.setShiftRule(shifts, false);
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(new Quote(List.of(), 11800, null, 0, 0, 0, 0, 11800, 0, List.of()));
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    var in = new SaleInput(riceCart(), List.of(new Pay("CASH", 11800, null)), null);
    service.sale(cashier, "k-off", in);
    verify(shifts, never()).requireOpen(any());
    service.setShiftRule(null, true);
    service.sale(cashier, "k-none", in); // required, but the shift module is not wired: billing still works
    verify(sales, Mockito.times(2)).insert(any(), anyString());
  }

  @Test void upiLink_withoutNote_buildsWithEmptyNote() {
    String link = service.upiLink(5000, null);
    assertThat(link).startsWith("upi://pay?pa=shop%40upi").contains("am=50.00");
  }

  @Test void receipt_cashierWithNullName_usesEmail() {
    UUID id = UUID.randomUUID();
    SaleRecord s = PosTestData.sale(id, null, List.of(PosTestData.item(UUID.randomUUID(), rice, 1, 5000, 5000, 0, 0)), 0, 0);
    when(sales.find(id)).thenReturn(Optional.of(s));
    when(users.findById(s.cashierId())).thenReturn(Optional.of(new User(s.cashierId(), "cashier@x", null, null, "CASHIER")));
    assertThat(service.receipt(id, 32)).contains("cashier@x");
  }
}
