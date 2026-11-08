package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnedSoFar;
import com.vijay.mall.pos.domain.PosRecords.SaleItemRecord;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.InvoiceCounterRepository;
import com.vijay.mall.pos.infra.PosSaleRepository;
import com.vijay.mall.wallet.app.WalletService;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PosReturnServiceCoverageTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID saleId = UUID.randomUUID();
  private final UUID itemId = UUID.randomUUID();
  private final UUID productId = UUID.randomUUID();

  private PosSaleRepository sales;
  private PosReturnService service;
  private SaleRecord sale;

  @BeforeEach
  void setUp() {
    sales = mock(PosSaleRepository.class);
    InvoiceCounterRepository counters = mock(InvoiceCounterRepository.class);
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    service = new PosReturnService(sales, mock(InventoryService.class), mock(WalletService.class), shop, counters,
        mock(AuditService.class), new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC),
        mock(CreditService.class));
    var item = new SaleItemRecord(itemId, productId, "Soap", SoldBy.UNIT, 4, 2500, 10000, 1000, 9000, 12, "3401", 8036, 482, 482, 0);
    // a walk-in sale: no customer, so no coins or credit are involved
    sale = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, null, null, 10000, null, 0, 0, 1000, 9000, 8036, 482, 482, 0, null,
        0, NOW.minusSeconds(30 * 86400L), List.of(item), List.of());
    when(sales.findReturnByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(sale));
    when(sales.returnedSoFar(eq(saleId), any())).thenReturn(new ReturnedSoFar(0, 0, 0));
    when(sales.addReturned(eq(itemId), anyInt())).thenReturn(true);
    when(counters.next("CREDIT_NOTE", "2026-27")).thenReturn(3L);
    when(sales.returnsOf(saleId)).thenAnswer(inv -> {
      ArgumentCaptor<ReturnRecord> c = ArgumentCaptor.forClass(ReturnRecord.class);
      verify(sales).insertReturn(c.capture(), anyString());
      return List.of(c.getValue());
    });
  }

  @Test void missingOrBlankIdempotencyKey_isRejected() {
    ReturnInput in = new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null);
    assertThatThrownBy(() -> service.refund(cashier, saleId, null, in)).isInstanceOf(ApiException.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> service.refund(cashier, false, saleId, "  ", in)).isInstanceOf(ApiException.class);
    verify(sales, never()).lockAndFind(any());
  }

  @Test void cashierReturnWindowOff_letsACashierTakeBackAnOldBill() {
    service.setCashierReturnDays(0);
    ReturnRecord r = service.refund(cashier, false, saleId, "old", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null));
    assertThat(r.refundPaise()).isEqualTo(2250);
  }

  @Test void emptyRefundList_meansTheWholeAmountGoesBackInCash() {
    ReturnRecord r = service.refund(cashier, true, saleId, "empty", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, List.of()));
    assertThat(r.refunds()).hasSize(1);
    assertThat(r.refunds().get(0).mode()).isEqualTo("CASH");
    assertThat(r.refunds().get(0).amountPaise()).isEqualTo(2250);
    assertThat(r.coinsClawedBack()).isZero();
    assertThat(r.creditAdjustedPaise()).isZero();
  }
}
