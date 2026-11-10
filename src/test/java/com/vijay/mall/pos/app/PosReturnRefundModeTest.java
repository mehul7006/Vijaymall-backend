package com.vijay.mall.pos.app;

import com.vijay.mall.common.tax.GstRates;
import static com.vijay.mall.pos.app.PosTestData.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosViews.Pay;
import com.vijay.mall.pos.app.PosViews.ReturnInput;
import com.vijay.mall.pos.app.PosViews.ReturnLine;
import com.vijay.mall.pos.domain.PosRecords;
import com.vijay.mall.pos.domain.PosRecords.PaymentRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnRecord;
import com.vijay.mall.pos.domain.PosRecords.ReturnedSoFar;
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

/** How a return is paid out: the bill's own mode by default, a chosen refundMode, or an explicit split. */
class PosReturnRefundModeTest {
  private final UUID cashier = UUID.randomUUID();
  private final UUID saleId = UUID.randomUUID();
  private final UUID itemId = UUID.randomUUID();
  private PosSaleRepository sales;
  private PosReturnService service;

  @BeforeEach
  void setUp() {
    sales = mock(PosSaleRepository.class);
    InvoiceCounterRepository counters = mock(InvoiceCounterRepository.class);
    ShopService shop = mock(ShopService.class);
    when(shop.get()).thenReturn(PosTestData.shop());
    service = new PosReturnService(sales, mock(InventoryService.class), mock(WalletService.class), shop, counters, mock(AuditService.class),
        new TransactionTemplate(mock(PlatformTransactionManager.class)), Clock.fixed(NOW, ZoneOffset.UTC), mock(CreditService.class));
    when(sales.findReturnByIdem(anyString())).thenReturn(Optional.empty());
    when(sales.returnedSoFar(eq(saleId), any())).thenReturn(new ReturnedSoFar(0, 0, 0));
    when(sales.addReturned(eq(itemId), anyInt())).thenReturn(true);
    when(counters.next("CREDIT_NOTE", "2026-27")).thenReturn(1L);
    when(sales.returnsOf(saleId)).thenAnswer(inv -> {
      if (org.mockito.Mockito.mockingDetails(sales).getInvocations().stream().noneMatch(i -> i.getMethod().getName().equals("insertReturn"))) return List.of();
      ArgumentCaptor<ReturnRecord> c = ArgumentCaptor.forClass(ReturnRecord.class);
      verify(sales, org.mockito.Mockito.atLeastOnce()).insertReturn(c.capture(), anyString());
      return List.of(c.getValue());
    });
  }

  private void billPaid(PaymentRecord... payments) {
    var item = new PosRecords.SaleItemRecord(itemId, UUID.randomUUID(), "Soap", SoldBy.UNIT, 4, 2500, 10000, 1000, 9000, GstRates.of(12), "3401",
        8036, 482, 482, 0);
    var sale = new SaleRecord(saleId, "VSM/2026-27/000007", cashier, null, null, 10000, null, 0, 0, 1000, 9000, 8036, 482, 482, 0, null, 0,
        NOW, List.of(item), List.of(payments));
    when(sales.lockAndFind(saleId)).thenReturn(Optional.of(sale));
  }

  private ReturnRecord refund(String refundMode, List<Pay> refunds) {
    return service.refund(cashier, saleId, UUID.randomUUID().toString(),
        new ReturnInput(List.of(new ReturnLine(itemId, 1)), null, refunds, refundMode));
  }

  private static PaymentRecord paid(String mode, long amount) { return new PaymentRecord(mode, amount, null); }

  @Test void upiBillRefundsAsUpiByDefault() {
    billPaid(paid("UPI", 9000));
    var r = refund(null, null);
    assertThat(r.refunds()).extracting(PaymentRecord::mode, PaymentRecord::amountPaise).containsExactly(tuple("UPI", 2250L));
  }

  @Test void cardBillRefundsAsCard_andTwoPartsOfTheSameModeStillCount() {
    billPaid(paid("card", 4000), paid("CARD", 5000));
    assertThat(refund("  ", List.of()).refunds()).extracting(PaymentRecord::mode).containsExactly("CARD");
  }

  @Test void splitBillCreditBillAndBillWithoutPaymentsRefundAsCash() {
    billPaid(paid("CASH", 4000), paid("UPI", 5000));
    assertThat(refund(null, null).refunds()).extracting(PaymentRecord::mode).containsExactly("CASH");
    billPaid(paid("CREDIT", 9000));
    assertThat(mode(refund(null, null))).isEqualTo("CASH");
    billPaid();
    assertThat(mode(refund(null, null))).isEqualTo("CASH");
  }

  private static String mode(ReturnRecord r) { return r.refunds().get(0).mode(); }

  @Test void refundModeOverridesTheBillMode_andIsNormalised() {
    billPaid(paid("UPI", 9000));
    assertThat(mode(refund(" cash ", null))).isEqualTo("CASH");
    assertThat(mode(refund("card", null))).isEqualTo("CARD");
  }

  @Test void explicitRefundsStillWork_withModesNormalised() {
    billPaid(paid("UPI", 9000));
    var r = refund(null, List.of(new Pay("cash", 1000, null), new Pay(" upi", 1250, "u")));
    assertThat(r.refunds()).extracting(PaymentRecord::mode).containsExactly("CASH", "UPI");
  }

  @Test void refundModeTogetherWithRefundsIsRejected() {
    billPaid(paid("UPI", 9000));
    var e = catchApi(() -> refund("CASH", List.of(new Pay("CASH", 2250, null))));
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    assertThat(PayModesTest.fields(e)).containsKey("refundMode");
  }

  @Test void badRefundModeIsRejectedWithTheValueReceived() {
    billPaid(paid("UPI", 9000));
    var e = catchApi(() -> refund("credit", null));
    assertThat(PayModesTest.fields(e)).containsEntry("refundMode", "payment mode must be CASH, UPI or CARD (got \"credit\")");
  }

  @Test void badModeInARefundLineNamesTheIndexedField() {
    billPaid(paid("UPI", 9000));
    var e = catchApi(() -> refund(null, List.of(new Pay("CASH", 1000, null), new Pay("bitcoin", 1250, null))));
    assertThat(PayModesTest.fields(e)).containsEntry("refunds[1].mode", "payment mode must be CASH, UPI or CARD (got \"bitcoin\")");
    var n = catchApi(() -> refund(null, List.of(new Pay(null, 2250, null))));
    assertThat(PayModesTest.fields(n)).containsKey("refunds[0].mode");
  }

  private static ApiException catchApi(Runnable r) { return PayModesTest.catchApi(r); }
}
