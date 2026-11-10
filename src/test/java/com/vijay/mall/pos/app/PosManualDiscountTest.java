package com.vijay.mall.pos.app;

import com.vijay.mall.common.tax.GstRates;
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
import com.vijay.mall.pos.app.PosViews.ManualDiscount;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.PosRecords.HoldRecord;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.PosRecords;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosHoldRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Manual discounts at the counter: who may give them, what they need and what ends up on the bill. */
class PosManualDiscountTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID manager = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();

  private QuoteService quotes;
  private PosSaleRepository sales;
  private ApprovalService approvals;
  private PosService service;

  @BeforeEach
  void setUp() {
    PosCatalogService catalog = mock(PosCatalogService.class);
    quotes = mock(QuoteService.class);
    sales = mock(PosSaleRepository.class);
    approvals = mock(ApprovalService.class);
    ShopService shop = mock(ShopService.class);
    InvoiceCounterRepository counters = mock(InvoiceCounterRepository.class);
    service = new PosService(catalog, quotes, mock(InventoryService.class), mock(WalletService.class), mock(PromotionService.class),
        mock(UserRepository.class), shop, sales, mock(PosHoldRepository.class), counters, mock(AuditService.class),
        JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)),
        Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
    service.setDiscountRule(approvals, 5); // a cashier may give 5% alone
    when(shop.get()).thenReturn(PosTestData.shop());
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    when(catalog.productsById(any())).thenReturn(Map.of(rice, riceData));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, GstRates.of(5), "1006")));
    when(counters.next("INVOICE", "2026-27")).thenReturn(42L);
    when(sales.findByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
    when(quotes.quoteLines(any(), anyList(), any(), any(), any()))
        .thenReturn(new Quote(List.of(), 11800, null, 0, 0, 0, 0, 11800, 0, List.of())); // 2 x Rs 59
  }

  private SaleInput sale(long payment, ManualDiscount md) {
    var cart = new Cart(List.of(new Item(rice, 2)), null, null, null, null);
    return new SaleInput(cart, List.of(new Pay("CASH", payment, null)), null, md);
  }

  private SaleRecord stored() {
    ArgumentCaptor<SaleRecord> c = ArgumentCaptor.forClass(SaleRecord.class);
    verify(sales).insert(c.capture(), anyString());
    return c.getValue();
  }

  @Test void anAdminMayGiveAnyDiscountAndIsTheApprover() {
    service.sale(manager, true, "k1", sale(10800, new ManualDiscount(1000, " damaged pack ", null)));
    SaleRecord s = stored();
    assertThat(s.totalPaise()).isEqualTo(10800);
    assertThat(s.subtotalPaise()).isEqualTo(11800);
    assertThat(s.manualDiscountPaise()).isEqualTo(1000);
    assertThat(s.manualDiscountReason()).isEqualTo("damaged pack");
    assertThat(s.manualDiscountApprover()).isEqualTo(manager);
    assertThat(s.taxablePaise() + s.cgstPaise() + s.sgstPaise()).as("GST is worked out on what was really charged").isEqualTo(10800);
    verify(approvals, never()).approverFor(any(), any());
  }

  @Test void aCashierMayGiveUpToTheirOwnLimitWithoutAPin() {
    service.sale(cashier, false, "k2", sale(11210, new ManualDiscount(590, "loyal customer", null))); // exactly 5% of 11800
    SaleRecord s = stored();
    assertThat(s.manualDiscountPaise()).isEqualTo(590);
    assertThat(s.manualDiscountApprover()).as("nobody else approved it").isNull();
    verify(approvals, never()).approverFor(any(), any());
  }

  @Test void aboveTheLimitACashierNeedsAManagersPinAndTheManagerIsRecorded() {
    when(approvals.approverFor(cashier, "1234")).thenReturn(Optional.of(manager));
    service.sale(cashier, false, "k3", sale(11209, new ManualDiscount(591, "price match", "1234")));
    SaleRecord s = stored();
    assertThat(s.manualDiscountPaise()).isEqualTo(591);
    assertThat(s.manualDiscountApprover()).isEqualTo(manager);
  }

  @Test void aMissingOrWrongPinRefusesTheBillAndTakesNothing() {
    when(approvals.approverFor(any(), any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.sale(cashier, false, "k4", sale(11000, new ManualDiscount(800, "why", null))))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("DISCOUNT_NEEDS_APPROVAL");
          assertThat(e.getMessage()).contains("PIN is needed");
        });
    assertThatThrownBy(() -> service.sale(cashier, false, "k5", sale(11000, new ManualDiscount(800, "why", "0000"))))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("DISCOUNT_NEEDS_APPROVAL");
          assertThat(e.getMessage()).contains("not valid");
        });
    assertThatThrownBy(() -> service.sale(cashier, false, "k5b", sale(11000, new ManualDiscount(800, "why", "  "))))
        .isInstanceOf(ApiException.class);
    verify(sales, never()).insert(any(), anyString());
  }

  @Test void withoutAnyApprovalSetUpACashierCannotGiveAMoreThanTheirLimit() {
    service.setDiscountRule(null, 0);
    assertThatThrownBy(() -> service.sale(cashier, false, "k6", sale(11700, new ManualDiscount(100, "why", "1234"))))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DISCOUNT_NEEDS_APPROVAL"));
  }

  @Test void aDiscountNeedsAReasonAndMustBeSensible() {
    for (ManualDiscount bad : new ManualDiscount[] {new ManualDiscount(100, null, null), new ManualDiscount(100, "  ", null),
        new ManualDiscount(100, "x".repeat(101), null), new ManualDiscount(-5, "why", null), new ManualDiscount(11801, "why", null)}) {
      assertThatThrownBy(() -> service.sale(manager, true, "kx" + bad.amountPaise(), sale(11800, bad))).isInstanceOf(ApiException.class);
    }
    verify(sales, never()).insert(any(), anyString());
  }

  @Test void aZeroOrMissingDiscountChangesNothing() {
    service.sale(manager, true, "k7", sale(11800, new ManualDiscount(0, null, null)));
    assertThat(stored().manualDiscountPaise()).isZero();
  }

  @Test void theDiscountIsTakenOffAfterCouponAndCoinsAndCannotExceedWhatIsLeft() {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any()))
        .thenReturn(new Quote(List.of(), 11800, "SAVE", 800, 10, 1000, 0, 10000, 0, List.of()));
    assertThatThrownBy(() -> service.sale(manager, true, "k8", sale(0, new ManualDiscount(10001, "why", null)))).isInstanceOf(ApiException.class);
  }

  @Test void theReceiptShowsTheDiscountLine() {
    var item = new PosRecords.SaleItemRecord(UUID.randomUUID(), rice, "Rice", SoldBy.UNIT, 2, 5900, 11800, 1000,
        10800, GstRates.of(5), "1006", 10286, 257, 257, 0);
    var rec = new SaleRecord(UUID.randomUUID(), "VSM/2026-27/000042", cashier, null, null, 11800, null, 0, 0, 0, 10800, 10286, 257, 257, 0,
        null, 0, NOW, List.of(item), List.of(new PosRecords.PaymentRecord("CASH", 10800, null)), "CASH", 1000,
        "damaged pack", manager);
    when(sales.find(rec.id())).thenReturn(Optional.of(rec));
    assertThat(service.receipt(rec.id(), 32)).contains("Discount").contains("10.00");
  }
}
