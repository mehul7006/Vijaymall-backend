package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.app.PosViews.Cart;
import com.vijay.mall.pos.app.PosViews.SaleInput;
import com.vijay.mall.pos.domain.OfflineExceptionKind;
import com.vijay.mall.pos.domain.OfflineFinding;
import com.vijay.mall.pos.domain.PosRecords.SaleRecord;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PosSyncServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID cashier = UUID.randomUUID();
  private PosService pos;
  private PosCatalogService catalog;
  private OfflineExceptionService exceptions;
  private PosSyncService service;

  @BeforeEach
  void setUp() {
    pos = mock(PosService.class);
    catalog = mock(PosCatalogService.class);
    exceptions = mock(OfflineExceptionService.class);
    service = new PosSyncService(pos, catalog, exceptions, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static PosSyncService.OfflineSale bill(String key) {
    return new PosSyncService.OfflineSale(key, NOW.minusSeconds(600), new Cart(List.of(), null, null, null, null), List.of(), null, null);
  }

  private SaleRecord saved(String invoice) {
    SaleRecord s = mock(SaleRecord.class);
    when(s.id()).thenReturn(UUID.randomUUID());
    when(s.invoiceNo()).thenReturn(invoice);
    return s;
  }

  @Test
  void aNewBillIsRungUpAsOfflineWithItsOwnSaleTime() {
    when(pos.findByIdempotencyKey("k1")).thenReturn(Optional.empty());
    SaleRecord created = saved("VSM/2026-27/000001");
    when(pos.sale(eq(cashier), eq(false), eq("k1"), any())).thenReturn(created);
    var r = service.upload(cashier, false, List.of(bill("k1"))).get(0);
    assertThat(r.status()).isEqualTo("CREATED");
    assertThat(r.invoiceNo()).isEqualTo("VSM/2026-27/000001");
    ArgumentCaptor<SaleInput> in = ArgumentCaptor.forClass(SaleInput.class);
    verify(pos).sale(eq(cashier), eq(false), eq("k1"), in.capture());
    assertThat(in.getValue().offline()).isTrue();
    assertThat(in.getValue().soldAt()).isEqualTo(NOW.minusSeconds(600));
  }

  @Test
  void aBillAlreadyRecordedIsReportedNotRungUpAgain() {
    SaleRecord earlier = saved("VSM/2026-27/000007");
    when(pos.findByIdempotencyKey("k2")).thenReturn(Optional.of(earlier));
    var r = service.upload(cashier, true, List.of(bill("k2"))).get(0);
    assertThat(r.status()).isEqualTo("DUPLICATE");
    assertThat(r.saleId()).isEqualTo(earlier.id());
    verify(pos, never()).sale(any(), anyBoolean(), any(), any());
  }

  @Test
  void aRefusedOrBrokenBillIsReportedAndTheNextOneStillRuns() {
    when(pos.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(pos.sale(eq(cashier), eq(false), eq("bad"), any())).thenThrow(ApiException.validation("payments", "do not add up"));
    when(pos.sale(eq(cashier), eq(false), eq("boom"), any())).thenThrow(new IllegalStateException("db down"));
    SaleRecord fine = saved("VSM/2026-27/000009");
    when(pos.sale(eq(cashier), eq(false), eq("fine"), any())).thenReturn(fine);
    var results = service.upload(cashier, false, List.of(bill("bad"), bill("boom"), bill("fine")));
    assertThat(results).extracting(PosSyncService.Result::status).containsExactly("REJECTED", "REJECTED", "CREATED");
    assertThat(results.get(0).errorCode()).isEqualTo("VALIDATION_FAILED");
    assertThat(results.get(1).errorCode()).isEqualTo("INTERNAL_ERROR");
    assertThat(results.get(1).message()).doesNotContain("db down");
  }

  @Test
  void missingKeysAndMissingBillsAreRefusedWithoutTouchingTheTill() {
    var results = service.upload(cashier, false, new ArrayList<>(java.util.Arrays.asList(bill(null), bill(" "), null)));
    assertThat(results).allSatisfy(r -> {
      assertThat(r.status()).isEqualTo("REJECTED");
      assertThat(r.errorCode()).isEqualTo("VALIDATION_FAILED");
    });
    verify(pos, never()).sale(any(), anyBoolean(), any(), any());
  }

  @Test
  void aBatchMustHaveBetweenOneAndFiftyBills() {
    assertThatThrownBy(() -> service.upload(cashier, false, null)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.upload(cashier, false, List.of())).isInstanceOf(ApiException.class);
    var fiftyOne = java.util.stream.IntStream.range(0, 51).mapToObj(i -> bill("k" + i)).toList();
    assertThatThrownBy(() -> service.upload(cashier, false, fiftyOne)).isInstanceOf(ApiException.class);
  }

  @Test
  void theStatusReportsTimeVersionAndLimits() {
    when(catalog.catalogVersion()).thenReturn(NOW.minusSeconds(60));
    var s = service.status();
    assertThat(s.serverTime()).isEqualTo(NOW);
    assertThat(s.catalogVersion()).isEqualTo(NOW.minusSeconds(60));
    assertThat(s.batchLimit()).isEqualTo(50);
    assertThat(s.maxOfflineDays()).isEqualTo(7);
  }

  @Test
  void saleTimesMustBeRecentAndNotInTheFuture() {
    assertThat(PosService.soldAt(null, NOW)).isEqualTo(NOW);
    assertThat(PosService.soldAt(NOW.minusSeconds(6 * 86400L), NOW)).isEqualTo(NOW.minusSeconds(6 * 86400L));
    assertThat(PosService.soldAt(NOW.plusSeconds(120), NOW)).as("a little clock drift is fine").isEqualTo(NOW.plusSeconds(120));
    assertThatThrownBy(() -> PosService.soldAt(NOW.minusSeconds(8 * 86400L), NOW)).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> PosService.soldAt(NOW.plusSeconds(3600), NOW)).isInstanceOf(ApiException.class);
  }

  private static PosSyncService.OfflineSale rungBill(String key, Cart cart) {
    return new PosSyncService.OfflineSale(key, NOW.minusSeconds(600), cart, List.of(), null, null,
        new PosViews.Rung(List.of(new PosViews.RungLine(UUID.randomUUID(), 1, 100L, 5, null, null, null))));
  }

  @Test
  void aBillWithARungSnapshotIsPassedOnAndItsExceptionsAreReported() {
    when(pos.findByIdempotencyKey("r1")).thenReturn(Optional.empty());
    SaleRecord created = saved("VSM/2026-27/000011");
    when(pos.sale(eq(cashier), eq(false), eq("r1"), any())).thenReturn(created);
    when(exceptions.forSale(created.id())).thenReturn(List.of(new OfflineFinding(OfflineExceptionKind.PRICE_CHANGED, "price moved", null, "1", "2")));
    var r = service.upload(cashier, false, List.of(rungBill("r1", null))).get(0);
    assertThat(r.status()).isEqualTo("CREATED");
    assertThat(r.exceptions()).containsExactly(new PosSyncService.ExceptionView("PRICE_CHANGED", "price moved"));
    ArgumentCaptor<SaleInput> in = ArgumentCaptor.forClass(SaleInput.class);
    verify(pos).sale(eq(cashier), eq(false), eq("r1"), in.capture());
    assertThat(in.getValue().rung()).isNotNull();
    assertThat(in.getValue().cart()).as("an empty cart stands in when only the snapshot was sent").isNotNull();
    assertThat(in.getValue().offline()).isTrue();
  }

  @Test
  void aSnapshotBillKeepsTheCartItCameWith() {
    when(pos.findByIdempotencyKey("r2")).thenReturn(Optional.empty());
    SaleRecord created = saved("VSM/2026-27/000012");
    when(pos.sale(eq(cashier), eq(false), eq("r2"), any())).thenReturn(created);
    Cart cart = new Cart(null, "9000000001", null, null, null);
    var r = service.upload(cashier, false, List.of(rungBill("r2", cart))).get(0);
    assertThat(r.exceptions()).isEmpty();
    ArgumentCaptor<SaleInput> in = ArgumentCaptor.forClass(SaleInput.class);
    verify(pos).sale(eq(cashier), eq(false), eq("r2"), in.capture());
    assertThat(in.getValue().cart()).isSameAs(cart);
  }

  @Test
  void aLegacyBillWithoutACartIsStillHandedOnAsIsSoItIsRefused() {
    when(pos.findByIdempotencyKey("l1")).thenReturn(Optional.empty());
    when(pos.sale(eq(cashier), eq(false), eq("l1"), any())).thenThrow(ApiException.validation("cart", "is required"));
    var r = service.upload(cashier, false, List.of(new PosSyncService.OfflineSale("l1", null, null, List.of(), null, null))).get(0);
    assertThat(r.status()).isEqualTo("REJECTED");
    assertThat(r.exceptions()).isEmpty();
  }

  @Test
  void aDuplicateReportsTheExceptionsOfTheEarlierBill() {
    SaleRecord earlier = saved("VSM/2026-27/000013");
    when(pos.findByIdempotencyKey("d1")).thenReturn(Optional.of(earlier));
    when(exceptions.forSale(earlier.id())).thenReturn(List.of(new OfflineFinding(OfflineExceptionKind.OVERSOLD, "short", null, "0", "1")));
    var r = service.upload(cashier, false, List.of(rungBill("d1", null))).get(0);
    assertThat(r.status()).isEqualTo("DUPLICATE");
    assertThat(r.exceptions()).extracting(PosSyncService.ExceptionView::kind).containsExactly("OVERSOLD");
  }
}
