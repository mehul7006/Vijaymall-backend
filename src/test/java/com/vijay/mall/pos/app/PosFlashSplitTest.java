package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.Item;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
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

/** A counter basket larger than what a running flash sale has left is split into a sale line and a normal-price line. */
class PosFlashSplitTest {
  private final UUID rice = UUID.randomUUID();
  private final UUID sale = UUID.randomUUID();
  private QuoteService quotes;
  private FlashPricing flash;
  private PosService service;

  @BeforeEach
  void setUp() {
    PosCatalogService catalog = mock(PosCatalogService.class);
    quotes = mock(QuoteService.class);
    flash = mock(FlashPricing.class);
    ShopService shop = mock(ShopService.class);
    service = new PosService(catalog, quotes, mock(InventoryService.class), mock(WalletService.class), mock(PromotionService.class),
        mock(UserRepository.class), shop, mock(PosSaleRepository.class), mock(PosHoldRepository.class), mock(InvoiceCounterRepository.class),
        mock(AuditService.class), JsonMapper.builder().build(), new TransactionTemplate(mock(PlatformTransactionManager.class)),
        Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
    service.setFlash(flash);
    ProductData riceData = PosTestData.product(rice, "Rice", 5900, true);
    when(catalog.productsById(any())).thenReturn(Map.of(rice, riceData));
    when(catalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, 5, "1006")));
    when(quotes.quoteLines(any(), anyList(), any(), any(), any())).thenReturn(new Quote(List.of(), 0, null, 0, 0, 0, 0, 0, 0, List.of()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void theUnitsBeyondTheSaleStockAreBilledAtTheNormalPrice() {
    when(flash.apply(any(), any(), anyLong(), any(), anyInt())).thenReturn(Optional.of(new FlashPricing.Applied(sale, UUID.randomUUID(), 5000, 2, 5900)));

    var view = service.quote(new Cart(List.of(new Item(rice, 5)), null, null, null, null));

    ArgumentCaptor<List<PricedLine>> sent = ArgumentCaptor.forClass(List.class);
    verify(quotes).quoteLines(any(), sent.capture(), any(), any(), any());
    assertThat(sent.getValue()).extracting(PricedLine::qty).containsExactly(2, 3);
    assertThat(sent.getValue()).extracting(PricedLine::unitPricePaise).containsExactly(5000L, 5900L);
    assertThat(sent.getValue().get(0).flashSaleId()).isEqualTo(sale);
    assertThat(sent.getValue().get(1).flashSaleId()).isNull();
    assertThat(view.lines()).extracting(PosViews.BillLine::qty).containsExactly(2, 3);
    assertThat(view.subtotalPaise()).isEqualTo(2 * 5000 + 3 * 5900);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aBasketThatFitsTheSaleStaysOneLine() {
    when(flash.apply(any(), any(), anyLong(), any(), anyInt())).thenReturn(Optional.of(new FlashPricing.Applied(sale, UUID.randomUUID(), 5000, 5, 5900)));

    var view = service.quote(new Cart(List.of(new Item(rice, 5)), null, null, null, null));

    assertThat(view.lines()).hasSize(1);
    assertThat(view.subtotalPaise()).isEqualTo(25000);
  }
}
