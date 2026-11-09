package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ReturnItemRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleItemRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.SoldBy;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Quote with a manual discount, the round-off mode, payment mode handling on sales and the credit note receipt. */
class PosServiceCounterGapsTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private QuoteService quotes;
  private PosSaleRepository sales;
  private UserRepository users;
  private ShopService shop;
  private RuntimeSettings settings;
  private PosService service;

  @BeforeEach
  void setUp() {
    PosCatalogService catalog = mock(PosCatalogService.class);
    quotes = mock(QuoteService.class);
    sales = mock(PosSaleRepository.class);
    users = mock(UserRepository.class);
    shop = mock(ShopService.class);
    settings = mock(RuntimeSettings.class);
    InvoiceCounterRepository counters = mock(InvoiceCounterRepository.class);
    service = new PosService(catalog, quotes, mock(InventoryService.class), mock(WalletService.class), mock(PromotionService.class), users,
        shop, sales, mock(PosHoldRepository.class), counters, mock(AuditService.class), JsonMapper.builder().build(),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
    when(shop.get()).thenReturn(PosTestData.shop());
    when(catalog.productsById(any())).thenReturn(Map.of(rice, PosTestData.product(rice, "Rice", 5900, true)));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006")));
    when(counters.next("INVOICE", "2026-27")).thenReturn(42L);
    when(sales.findByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    // 2 x 59.00 = 118.00, no coupon or coins
    when(quotes.quoteLines(any(), anyList(), any(), any(), any()))
        .thenReturn(new Quote(List.of(), 11800, null, 0, 0, 0, 0, 11800, 0, List.of()));
  }

  private static Cart cart(UUID p) { return new Cart(List.of(new Item(p, 2)), null, null, null, null); }

  // ----- quote with a manual discount -----
  @Test void quoteWithoutManualDiscountIsUnchanged() {
    var v = service.quote(cart(rice));
    assertThat(v.totalPaise()).isEqualTo(11800);
    assertThat(v.manualDiscountPaise()).isZero();
    assertThat(service.quote(cart(rice), null).manualDiscountPaise()).isZero();
    assertThat(service.quote(cart(rice), new ManualDiscount(0, null, null)).totalPaise()).isEqualTo(11800);
  }

  @Test void quoteSubtractsTheManualDiscountWithoutReasonOrPin() {
    var v = service.quote(cart(rice), new ManualDiscount(1800, null, null));
    assertThat(v.manualDiscountPaise()).isEqualTo(1800);
    assertThat(v.totalPaise()).isEqualTo(10000);
    assertThat(v.taxablePaise() + v.cgstPaise() + v.sgstPaise()).isEqualTo(10000);
    assertThat(service.quote(cart(rice), new ManualDiscount(11800, "x", "1234")).totalPaise()).isZero();
  }

  @Test void quoteRoundsAfterTheManualDiscount() {
    service.setSettings(settings);
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NEAREST_RUPEE");
    var v = service.quote(cart(rice), new ManualDiscount(1830, null, null)); // 99.70 -> 100.00
    assertThat(v.roundOffPaise()).isEqualTo(30);
    assertThat(v.totalPaise()).isEqualTo(10000);
  }

  @Test void quoteRejectsNegativeAndTooLargeManualDiscounts() {
    assertThatThrownBy(() -> service.quote(cart(rice), new ManualDiscount(-1, null, null)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(PayModesTest.fields(e)).containsKey("manualDiscount"));
    assertThatThrownBy(() -> service.quote(cart(rice), new ManualDiscount(11801, null, null)))
        .hasMessageContaining("more than the bill");
  }

  // ----- round-off mode -----
  @Test void roundOffModeFollowsTheAdminSetting() {
    assertThat(service.roundOffMode()).as("no settings wired").isEqualTo("NONE");
    service.setSettings(settings);
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NEAREST_RUPEE");
    assertThat(service.roundOffMode()).isEqualTo("NEAREST_RUPEE");
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NONE");
    assertThat(service.roundOffMode()).isEqualTo("NONE");
  }

  // ----- payment modes on a sale -----
  private SaleRecord sale(List<Pay> pays) {
    return service.sale(cashier, true, "k-" + UUID.randomUUID(), new SaleInput(cart(rice), pays, null));
  }

  @Test void saleAcceptsModesWithSpacesAndLowerCase() {
    sale(List.of(new Pay(" cash ", 10000, null), new Pay("Upi", 1800, "u")));
    var c = org.mockito.ArgumentCaptor.forClass(SaleRecord.class);
    verify(sales).insert(c.capture(), anyString());
    assertThat(c.getValue().payments()).extracting(PaymentRecord::mode).containsExactly("CASH", "UPI");
    assertThat(c.getValue().paymentType()).isEqualTo("SPLIT");
  }

  @Test void saleWithBadOrMissingModeIs400OnTheIndexedField() {
    assertThatThrownBy(() -> sale(List.of(new Pay("CASH", 5000, null), new Pay("xyz", 6800, null))))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.status().value()).isEqualTo(400);
          assertThat(PayModesTest.fields(e)).containsEntry("payments[1].mode",
              "payment mode must be CASH, UPI or CARD, or CREDIT on a bill (got \"xyz\")");
        });
    assertThatThrownBy(() -> sale(List.of(new Pay(null, 11800, null))))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(PayModesTest.fields(e)).containsKey("payments[0].mode"));
    verify(sales, never()).insert(any(), anyString());
  }

  @Test void offlineRungBillNormalisesModesToo() {
    service.setOfflineExceptions(mock(OfflineExceptionService.class));
    var rung = new Rung(List.of(new RungLine(rice, 2, 5900L, 5, null, null, null)));
    service.sale(cashier, false, "k-rung", new SaleInput(new Cart(null, null, null, null, null), List.of(new Pay("card ", 11800, null)),
        null, null, null, true, rung));
    var c = org.mockito.ArgumentCaptor.forClass(SaleRecord.class);
    verify(sales).insert(c.capture(), anyString());
    assertThat(c.getValue().payments()).extracting(PaymentRecord::mode).containsExactly("CARD");
    assertThatThrownBy(() -> service.sale(cashier, false, "k-rung2", new SaleInput(new Cart(null, null, null, null, null),
        List.of(new Pay("cheque", 11800, null)), null, null, null, true, rung)))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(PayModesTest.fields(e)).containsKey("payments[0].mode"));
  }

  // ----- credit note receipt -----
  private final UUID saleId = UUID.randomUUID();
  private final UUID itemId = UUID.randomUUID();
  private final UUID returnId = UUID.randomUUID();

  private void billWithReturn(String mobile, String reason) {
    var item = new SaleItemRecord(itemId, rice, "Rice", SoldBy.UNIT, 2, 5900, 11800, 0, 11800, 5, "1006", 11238, 281, 281, 0);
    var s = new SaleRecord(saleId, "VSM/2026-27/000009", cashier, null, mobile, 11800, null, 0, 0, 0, 11800, 11238, 281, 281, 0, null, 0, NOW,
        List.of(item), List.of(new PaymentRecord("UPI", 11800, null)));
    when(sales.find(saleId)).thenReturn(Optional.of(s));
    var r = new ReturnRecord(returnId, "VSM-CN/2026-27/000004", saleId, cashier, reason, 5900, 5619, 140, 141, 0, 0, NOW,
        List.of(new ReturnItemRecord(itemId, 1, 5900, 5619, 140, 141)), List.of(new PaymentRecord("UPI", 5900, null)), 0);
    when(sales.returnsOf(saleId)).thenReturn(List.of(r));
    when(users.findById(cashier)).thenReturn(Optional.of(new User(cashier, "c@x", "Asha", null, "CASHIER")));
  }

  @Test void creditNoteReceiptNamesTheNoteTheBillTheLinesAndTheRefund() {
    billWithReturn("9000000001", "damaged");
    String t = service.creditNoteReceipt(saleId, returnId, 32);
    assertThat(t).contains("CREDIT NOTE", "VSM-CN/2026-27/000004", "VSM/2026-27/000009", "Asha", "9000000001", "Rice", "Returned 1 (5%)",
        "Refund total", "59.00", "Refund UPI", "Reason: damaged", "Taxable", "56.19");
  }

  @Test void creditNoteReceiptWithoutCustomerOrReason() {
    billWithReturn(null, null);
    String t = service.creditNoteReceipt(saleId, returnId, 48);
    assertThat(t).doesNotContain("Cust:", "Reason:");
  }

  @Test void creditNoteReceiptRejectsUnknownReturnBillAndWidth() {
    billWithReturn("9000000001", "x");
    assertThatThrownBy(() -> service.creditNoteReceipt(saleId, UUID.randomUUID(), 32))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));
    UUID other = UUID.randomUUID();
    when(sales.find(other)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.creditNoteReceipt(other, returnId, 32)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.creditNoteReceipt(saleId, returnId, 40))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(PayModesTest.fields(e)).containsKey("width"));
  }
}
