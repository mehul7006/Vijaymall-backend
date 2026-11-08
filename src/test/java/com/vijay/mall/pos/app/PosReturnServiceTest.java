package com.vijay.mall.pos.app;

import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnedSoFar;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PosReturnServiceTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID saleId = UUID.randomUUID();
  private final UUID itemId = UUID.randomUUID();
  private final UUID productId = UUID.randomUUID();
  private final UUID customer = UUID.randomUUID();

  private PosSaleRepository sales;
  private InventoryService inventory;
  private WalletService wallet;
  private InvoiceCounterRepository counters;
  private CreditService credit;
  private PosReturnService service;
  private SaleRecord sale;

  @BeforeEach
  void setUp() {
    sales = mock(PosSaleRepository.class);
    inventory = mock(InventoryService.class);
    wallet = mock(WalletService.class);
    counters = mock(InvoiceCounterRepository.class);
    credit = mock(CreditService.class);
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    service = new PosReturnService(sales, inventory, wallet, shop, counters, mock(AuditService.class),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC), credit);
    // 4 pieces at 2500 = 10000 gross, 9000 net after a 1000 discount; 12% GST; 20 coins earned, 10 coins used
    var item = new com.vijay.mall.pos.domain.PosRecords.SaleItemRecord(itemId, productId, "Soap", com.vijay.mall.pos.domain.SoldBy.UNIT,
        4, 2500, 10000, 1000, 9000, 12, "3401", 8036, 482, 482, 0);
    sale = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, customer, "9000000001", 10000, null, 0, 10, 1000, 9000, 8036, 482,
        482, 20, null, 0, NOW, List.of(item), List.of());
    when(sales.findReturnByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(sale));
    when(sales.returnedSoFar(eq(saleId), any())).thenReturn(new ReturnedSoFar(0, 0, 0));
    when(sales.addReturned(eq(itemId), anyInt())).thenReturn(true);
    when(counters.next("CREDIT_NOTE", "2026-27")).thenReturn(3L);
    when(wallet.clawback(eq(customer), anyInt(), any())).thenAnswer(i -> i.getArgument(1));
    ReturnRecord stored = mock(ReturnRecord.class);
    when(stored.id()).thenReturn(UUID.randomUUID());
    when(sales.returnsOf(saleId)).thenAnswer(inv -> {
      ArgumentCaptor<ReturnRecord> c = ArgumentCaptor.forClass(ReturnRecord.class);
      verify(sales).insertReturn(c.capture(), anyString());
      return List.of(c.getValue());
    });
  }

  private ReturnRecord insertedReturn() {
    ArgumentCaptor<ReturnRecord> c = ArgumentCaptor.forClass(ReturnRecord.class);
    verify(sales).insertReturn(c.capture(), anyString());
    return c.getValue();
  }

  @Test void partialReturn_refundsProportionalNet_putsStockBack_adjustsCoins() {
    ReturnRecord r = service.refund(cashier, saleId, "r1", new ReturnInput(List.of(new ReturnLine(itemId, 1)), " damaged ", null));
    assertThat(r.creditNoteNo()).isEqualTo("VSM-CN/2026-27/000003");
    assertThat(r.refundPaise()).isEqualTo(2250);
    assertThat(r.reason()).isEqualTo("damaged");
    assertThat(r.refunds()).hasSize(1);
    assertThat(r.refunds().get(0).mode()).isEqualTo("CASH");
    assertThat(r.taxablePaise() + r.cgstPaise() + r.sgstPaise()).isEqualTo(2250);
    assertThat(r.coinsClawedBack()).isEqualTo(5); // 20 * 2500/10000
    assertThat(r.coinsRestored()).isEqualTo(2);   // 10 * 2500/10000 rounded down
    verify(inventory).release(anyList(), any(UUID.class), eq("POS_RETURN"));
    verify(wallet).clawback(eq(customer), eq(5), any(UUID.class));
    verify(wallet).restore(eq(customer), eq(2), any(UUID.class));
  }

  @Test void cashierCannotTakeBackAnOldBillButAnAdminCan() {
    service.setCashierReturnDays(7);
    var old = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, customer, "9000000001", 10000, null, 0, 10, 1000, 9000, 8036, 482,
        482, 20, null, 0, NOW.minusSeconds(8 * 86400L), sale.items(), List.of());
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(old));
    var in = new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null);
    var e = org.junit.jupiter.api.Assertions.assertThrows(com.vijay.mall.common.error.ApiException.class,
        () -> service.refund(cashier, false, saleId, "old1", in));
    assertThat(e.code()).isEqualTo("RETURN_NEEDS_ADMIN");
    verify(inventory, org.mockito.Mockito.never()).release(anyList(), any(UUID.class), anyString());
    assertThat(service.refund(cashier, true, saleId, "old2", in).refundPaise()).isEqualTo(2250);
  }

  @Test void fullReturn_refundsAllNet_andAllCoins() {
    ReturnRecord r = service.refund(cashier, saleId, "r2", new ReturnInput(List.of(new ReturnLine(itemId, 2), new ReturnLine(itemId, 2)), null,
        List.of(new Pay("UPI", 4000, "x"), new Pay("CASH", 5000, null))));
    assertThat(r.refundPaise()).isEqualTo(9000);
    assertThat(r.coinsClawedBack()).isEqualTo(20);
    assertThat(r.coinsRestored()).isEqualTo(10);
    assertThat(r.reason()).isEmpty();
  }

  @Test void secondReturn_onlyMovesItsOwnShare() {
    var item2 = new com.vijay.mall.pos.domain.PosRecords.SaleItemRecord(itemId, productId, "Soap", com.vijay.mall.pos.domain.SoldBy.UNIT,
        4, 2500, 10000, 1000, 9000, 12, "3401", 8036, 482, 482, 1);
    sale = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, customer, "9000000001", 10000, null, 0, 10, 1000, 9000, 8036, 482,
        482, 20, null, 0, NOW, List.of(item2), List.of());
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(sale));
    when(sales.returnedSoFar(eq(saleId), any())).thenReturn(new ReturnedSoFar(2500, 5, 2));
    ReturnRecord r = service.refund(cashier, saleId, "r3", new ReturnInput(List.of(new ReturnLine(itemId, 3)), null, null));
    assertThat(r.refundPaise()).isEqualTo(6750);
    assertThat(r.coinsClawedBack()).isEqualTo(15);
    assertThat(r.coinsRestored()).isEqualTo(8);
  }

  @Test void saleWithoutCustomer_movesNoCoins() {
    var item = sale.items().get(0);
    sale = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, null, null, 10000, null, 0, 0, 1000, 9000, 8036, 482, 482, 0, null, 0,
        NOW, List.of(item), List.of());
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(sale));
    service.refund(cashier, saleId, "r4", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null));
    verify(wallet, never()).clawback(any(), anyInt(), any());
    verify(wallet, never()).restore(any(), anyInt(), any());
  }

  @Test void returningMoreThanLeft_isConflict() {
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r5", new ReturnInput(List.of(new ReturnLine(itemId, 5)), null, null)))
        .isInstanceOf(ApiException.class).hasMessageContaining("left to return");
    verify(inventory, never()).release(anyList(), any(), anyString());
  }

  @Test void raceOnCounter_isConflict() {
    when(sales.addReturned(eq(itemId), anyInt())).thenReturn(false);
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r6", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null)))
        .isInstanceOf(ApiException.class);
  }

  @Test void wrongRefundTotal_rejected() {
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r7",
        new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, List.of(new Pay("CASH", 1, null)))))
        .isInstanceOf(ApiException.class).hasMessageContaining("add up");
  }

  @Test void badLines_rejected() {
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r8", new ReturnInput(List.of(), null, null))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r8", new ReturnInput(null, null, null))).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r8", new ReturnInput(List.of(new ReturnLine(UUID.randomUUID(), 1)), null, null)))
        .hasMessageContaining("not on this bill");
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r8", new ReturnInput(List.of(new ReturnLine(null, 1)), null, null)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.refund(cashier, saleId, "r8", new ReturnInput(List.of(new ReturnLine(itemId, 0)), null, null)))
        .hasMessageContaining("positive");
  }

  @Test void unknownBill_isNotFound() {
    UUID other = UUID.randomUUID();
    when(sales.lockAndFind(other)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.refund(cashier, other, "r9", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null)))
        .isInstanceOf(ApiException.class);
  }

  @Test void missingKey_rejected_repeatedKeyReturnsFirst_raceReturnsWinner() {
    var in = new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null);
    assertThatThrownBy(() -> service.refund(cashier, saleId, " ", in)).isInstanceOf(ApiException.class);
    ReturnRecord first = mock(ReturnRecord.class);
    when(sales.findReturnByIdem("again")).thenReturn(Optional.of(first));
    assertThat(service.refund(cashier, saleId, "again", in)).isSameAs(first);

    ReturnRecord winner = mock(ReturnRecord.class);
    when(sales.findReturnByIdem("race")).thenReturn(Optional.empty(), Optional.of(winner));
    org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup")).when(sales).insertReturn(any(), eq("race"));
    assertThat(service.refund(cashier, saleId, "race", in)).isSameAs(winner);
  }

  @Test void returnsOf_delegates() {
    org.mockito.Mockito.doReturn(List.of()).when(sales).returnsOf(saleId);
    assertThat(service.returnsOf(saleId)).isEmpty();
  }

  // ---------- credit bills ----------
  private SaleRecord billWithCredit(long credit, long cash) {
    java.util.List<com.vijay.mall.pos.domain.PosRecords.PaymentRecord> pays = new java.util.ArrayList<>();
    if (cash > 0) pays.add(new com.vijay.mall.pos.domain.PosRecords.PaymentRecord("CASH", cash, null));
    pays.add(new com.vijay.mall.pos.domain.PosRecords.PaymentRecord("CREDIT", credit, null));
    SaleRecord s = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, customer, "9000000001", 10000, null, 0, 10, 1000, 9000,
        8036, 482, 482, 20, null, 0, NOW, sale.items(), pays);
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(s));
    return s;
  }

  @Test void returnOfACreditBill_reducesWhatIsOwedAndPaysOutNothing() {
    billWithCredit(9000, 0);
    when(credit.returnAdjustment(eq(customer), eq(saleId), eq(9000L), eq(2250L), any())).thenReturn(2250L);
    ReturnRecord r = service.refund(cashier, saleId, "cr1", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null));
    assertThat(r.refundPaise()).isEqualTo(2250);
    assertThat(r.creditAdjustedPaise()).isEqualTo(2250);
    assertThat(r.refunds()).isEmpty();
    verify(credit).recordReturn(eq(customer), eq(saleId), any(UUID.class), eq("VSM-CN/2026-27/000003"), eq(2250L), eq(cashier), any());
  }

  @Test void returnOfAHybridBill_paysOutOnlyTheRestInCashByDefault() {
    billWithCredit(1000, 8000);
    when(credit.returnAdjustment(eq(customer), eq(saleId), eq(1000L), eq(2250L), any())).thenReturn(1000L);
    ReturnRecord r = service.refund(cashier, saleId, "cr2", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null));
    assertThat(r.creditAdjustedPaise()).isEqualTo(1000);
    assertThat(r.refunds()).hasSize(1);
    assertThat(r.refunds().get(0).mode()).isEqualTo("CASH");
    assertThat(r.refunds().get(0).amountPaise()).isEqualTo(1250);
  }

  @Test void returnPayOutMustAddUpToTheRestAndSaysWhy() {
    billWithCredit(1000, 8000);
    when(credit.returnAdjustment(eq(customer), eq(saleId), eq(1000L), eq(2250L), any())).thenReturn(1000L);
    var in = new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, List.of(new com.vijay.mall.pos.app.PosViews.Pay("CASH", 2250, null)));
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.refund(cashier, saleId, "cr3", in))
        .isInstanceOf(com.vijay.mall.common.error.ApiException.class).hasMessageContaining("reduces the customer's credit");
    verify(credit, org.mockito.Mockito.never()).recordReturn(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
  }

  @Test void returnOfABillWithoutCredit_neverTouchesTheCreditAccount() {
    service.refund(cashier, saleId, "cr4", new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, null));
    verify(credit, org.mockito.Mockito.never()).returnAdjustment(any(), any(), org.mockito.ArgumentMatchers.anyLong(),
        org.mockito.ArgumentMatchers.anyLong(), any());
    assertThat(insertedReturn().creditAdjustedPaise()).isZero();
  }
}
