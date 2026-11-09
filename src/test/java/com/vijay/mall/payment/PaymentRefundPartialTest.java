package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.app.PaymentGatewayRegistry;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.infra.PaymentRepository;
import com.vijay.mall.support.FakeGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Part refunds of a captured payment (goods sent back) and the admin's refund list filter. */
class PaymentRefundPartialTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private PaymentRepository repo;
  private PaymentService service;
  private final UUID orderId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(PaymentRepository.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(0, 0));
    service = new PaymentService(repo, new PaymentGatewayRegistry(List.of(new FakeGateway()), props), mock(ApplicationEventPublisher.class),
        mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
  }

  private void payment(String status) {
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(new PaymentRepository.Payment(paymentId, orderId, "fake", 10000, status,
        "fake_" + orderId, "pay_1")));
  }

  @Test
  void theAmountMustBePositive() {
    assertThatThrownBy(() -> service.refundPartial(orderId, 0, "r")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.refundPartial(orderId, -5, "r")).isInstanceOf(ApiException.class);
    verify(repo, never()).insertRefund(any(), anyLong(), anyString(), any(), any(), any());
  }

  @Test
  void anOrderWithoutACapturedPaymentHasNothingToRefund() {
    when(repo.latestForOrder(orderId)).thenReturn(Optional.empty());
    assertThat(service.refundPartial(orderId, 100, "r")).isFalse();
    payment("CREATED");
    assertThat(service.refundPartial(orderId, 100, "r")).isFalse();
    verify(repo, never()).insertRefund(any(), anyLong(), anyString(), any(), any(), any());
  }

  @Test
  void neverRefundsMoreThanWasPaidIncludingEarlierRefunds() {
    payment("CAPTURED");
    when(repo.refundedSoFar(paymentId)).thenReturn(9000L);
    assertThatThrownBy(() -> service.refundPartial(orderId, 1001, "r")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("REFUND_EXCEEDS_PAYMENT"));
    verify(repo, never()).insertRefund(any(), anyLong(), anyString(), any(), any(), any());
  }

  @Test
  void aPartRefundIsQueuedAndTheStatusStaysCaptured() {
    payment("CAPTURED");
    when(repo.refundedSoFar(paymentId)).thenReturn(1000L);
    assertThat(service.refundPartial(orderId, 2000, "return VSM-1")).isTrue();
    verify(repo).insertRefund(paymentId, 2000, "PENDING", null, "return VSM-1", NOW);
    verify(repo, never()).setStatus(any(), eq("REFUNDED"), any(), any(), any());
  }

  @Test
  void refundingTheLastPaiseMarksThePaymentRefunded() {
    payment("CAPTURED");
    when(repo.refundedSoFar(paymentId)).thenReturn(7000L);
    assertThat(service.refundPartial(orderId, 3000, "r")).isTrue();
    verify(repo).setStatus(paymentId, "REFUNDED", null, null, NOW);
  }

  @Test
  void refundListFilterIsOptionalTrimmedAndValidated() {
    service.refunds(null, -3, 10);
    service.refunds("   ", 0, 10);
    verify(repo, Mockito.times(2)).refundPage(isNull(), eq(0), anyInt());
    service.refunds(" failed ", 2, 10);
    verify(repo).refundPage(eq("FAILED"), eq(2), anyInt());
    assertThatThrownBy(() -> service.refunds("bogus", 0, 10)).isInstanceOf(ApiException.class);
  }
}
