package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
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
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Rounding the counter bill to the rupee (admin setting gst.roundOff). */
class PosRoundOffTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID rice = UUID.randomUUID();
  private QuoteService quotes;
  private PosSaleRepository sales;
  private RuntimeSettings settings;
  private PosService service;

  @BeforeEach
  void setUp() {
    PosCatalogService catalog = mock(PosCatalogService.class);
    quotes = mock(QuoteService.class);
    sales = mock(PosSaleRepository.class);
    settings = mock(RuntimeSettings.class);
    ShopService shop = mock(ShopService.class);
    InvoiceCounterRepository counters = mock(InvoiceCounterRepository.class);
    service = new PosService(catalog, quotes, mock(InventoryService.class), mock(WalletService.class), mock(PromotionService.class),
        mock(UserRepository.class), shop, sales, mock(PosHoldRepository.class), counters, mock(AuditService.class), JsonMapper.builder().build(),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
    service.setSettings(settings);
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NEAREST_RUPEE");
    when(shop.get()).thenReturn(PosTestData.shop());
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    when(catalog.productsById(any())).thenReturn(Map.of(rice, riceData));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006")));
    when(counters.next("INVOICE", "2026-27")).thenReturn(42L);
    when(sales.findByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.find(any())).thenReturn(Optional.of(mock(SaleRecord.class)));
  }

  /** 2 x Rs 59.00 = 11800, less coins worth {@code coinPaise}. */
  private void billOf(long coinPaise) {
    when(quotes.quoteLines(any(), anyList(), any(), any(), any()))
        .thenReturn(new Quote(List.of(), 11800, null, 0, 1, coinPaise, 0, 11800 - coinPaise, 0, List.of()));
  }

  private SaleInput sale(long pay) {
    return new SaleInput(new Cart(List.of(new Item(rice, 2)), "9000000001", null, null, null), List.of(new Pay("CASH", pay, null)), null);
  }

  private SaleRecord stored() {
    ArgumentCaptor<SaleRecord> c = ArgumentCaptor.forClass(SaleRecord.class);
    verify(sales).insert(c.capture(), anyString());
    return c.getValue();
  }

  @Test
  void aBillDownToTheRupeeIsRoundedAndThePaymentMustMatchTheRoundedAmount() {
    billOf(30); // 117.70 -> 118.00 (+0.30)
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NEAREST_RUPEE");
    var view = service.quote(new Cart(List.of(new Item(rice, 2)), "9000000001", null, com.vijay.mall.wallet.domain.CoinRedemption.Mode.ALL, null));
    assertThat(view.totalPaise()).isEqualTo(11800);
    assertThat(view.roundOffPaise()).isEqualTo(30);
    service.sale(cashier, "k1", sale(11800));
    SaleRecord s = stored();
    assertThat(s.totalPaise()).isEqualTo(11800);
    assertThat(s.roundOffPaise()).isEqualTo(30);
    assertThat(s.taxablePaise() + s.cgstPaise() + s.sgstPaise() + s.roundOffPaise()).as("invoice value + rounding = total").isEqualTo(s.totalPaise());
  }

  @Test
  void halfARupeeRoundsUpAndLessThanHalfRoundsDown() {
    billOf(50); // 117.50 -> 118.00 (+0.50)
    service.sale(cashier, "k2", sale(11800));
    assertThat(stored().roundOffPaise()).isEqualTo(50);
  }

  @Test
  void aBillJustUnderHalfARupeeIsRoundedDown() {
    billOf(51); // 117.49 -> 117.00 (-0.49)
    service.sale(cashier, "k3", sale(11700));
    SaleRecord s = stored();
    assertThat(s.totalPaise()).isEqualTo(11700);
    assertThat(s.roundOffPaise()).isEqualTo(-49);
  }

  @Test
  void payingTheUnroundedAmountIsRefused() {
    billOf(30);
    assertThatThrownBy(() -> service.sale(cashier, "k4", sale(11770))).isInstanceOf(ApiException.class).hasMessageContaining("payments add up");
  }

  @Test
  void aWholeRupeeBillHasNoRoundOff() {
    billOf(0);
    service.sale(cashier, "k5", sale(11800));
    assertThat(stored().roundOffPaise()).isZero();
  }

  @Test
  void switchedOffOrWithoutSettingsNothingIsRounded() {
    billOf(30);
    when(settings.get(Settings.GST_ROUND_OFF)).thenReturn("NONE");
    service.sale(cashier, "k6", sale(11770));
    SaleRecord s = stored();
    assertThat(s.totalPaise()).isEqualTo(11770);
    assertThat(s.roundOffPaise()).isZero();
    service.setSettings(null);
    var view = service.quote(new Cart(List.of(new Item(rice, 2)), null, null, null, null));
    assertThat(view.roundOffPaise()).isZero();
  }
}
