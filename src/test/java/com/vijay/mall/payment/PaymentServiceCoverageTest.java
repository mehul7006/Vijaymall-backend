package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.payment.app.PaymentGatewayRegistry;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.payment.infra.PaymentRepository;
import com.vijay.mall.support.FakeGateway;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** Money paths not covered by the main payment tests: legacy FAILED payments, late failures, broken after-commit work. */
class PaymentServiceCoverageTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private PaymentRepository repo;
  private ApplicationEventPublisher events;
  private PlatformTransactionManager txManager;
  private PaymentService service;
  private final UUID orderId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(PaymentRepository.class);
    events = mock(ApplicationEventPublisher.class);
    txManager = mock(PlatformTransactionManager.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(0, 0));
    service = new PaymentService(repo, new PaymentGatewayRegistry(List.of(new FakeGateway()), props), events,
        mock(AuditService.class), Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(txManager));
    when(repo.recordEvent(any(), any(), any(), any(), any())).thenReturn(true);
  }

  private PaymentRepository.Payment payment(String status, long amount) {
    return new PaymentRepository.Payment(paymentId, orderId, "fake", amount, status, "fake_" + orderId, "pay_1");
  }

  private static String body(String id, String type, String providerOrderId, long amount) {
    return "{\"eventId\":\"" + id + "\",\"type\":\"" + type + "\",\"providerOrderId\":\"" + providerOrderId + "\",\"amount\":" + amount + "}";
  }

  private void webhook(String id, String type, long amount) {
    service.handleWebhook("fake", body(id, type, "fake_" + orderId, amount), Map.of("x-fake-signature", "ok"));
  }

  @Test
  void aPaymentLeftFailedByAnOlderVersionIsStillCapturedWhenMoneyArrives() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("FAILED", 5000)));
    webhook("c1", "captured", 5000);
    verify(repo).setStatus(paymentId, "CAPTURED", "pay_c1", null, NOW);
    verify(events).publishEvent(new PaymentEvents.PaymentCaptured(orderId, "pay_c1"));
  }

  @Test
  void aRefundedOrMismatchedPaymentIsNotCapturedByALateWebhook() {
    for (String status : new String[] {"REFUNDED", "MISMATCH"}) {
      when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment(status, 5000)));
      webhook("late-" + status, "captured", 5000);
    }
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(repo, never()).insertRefund(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void aFailureReportedForAnAlreadyCapturedPaymentChangesNothing() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CAPTURED", 5000)));
    webhook("f1", "failed", 0);
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
  }

  @Test
  void aWebhookForAnUnknownProviderOrderIsRecordedAndIgnored() {
    when(repo.byProviderOrder(any(), any())).thenReturn(Optional.empty());
    webhook("c2", "captured", 5000);
    webhook("f2", "failed", 0);
    webhook("i1", "something-else", 0);
    verify(repo, org.mockito.Mockito.times(3)).recordEvent(eq("fake"), any(), any(), any(), eq(NOW));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void aBrokenAfterCommitStepDoesNotUndoTheQueuedRefund() {
    // The provider call is made after the commit; if even opening its transaction fails, the refund stays queued
    // for the 30 second job and the cancel itself still succeeds.
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CAPTURED", 7500)));
    when(txManager.getTransaction(any())).thenThrow(new TransactionException("db down") { });

    boolean queued = service.refund(orderId, "order cancelled");

    assertThat(queued).isTrue();
    verify(repo).insertRefund(eq(paymentId), eq(7500L), eq("PENDING"), eq(null), eq("order cancelled"), eq(NOW));
    verify(repo).setStatus(paymentId, "REFUNDED", null, null, NOW);
    verify(repo, never()).pendingRefunds(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void aMismatchedCaptureStillQueuesTheFullRefundWhenTheAfterCommitStepFails() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    when(txManager.getTransaction(any())).thenThrow(new TransactionException("db down") { });
    webhook("m1", "captured", 4000);
    verify(repo).setStatus(paymentId, "MISMATCH", "pay_m1", "amount mismatch", NOW);
    verify(repo).insertRefund(paymentId, 4000, "PENDING", null, "amount mismatch", NOW);
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void nothingIsRefundedForAnUncapturedOrMissingPayment() {
    when(repo.latestForOrder(orderId)).thenReturn(Optional.empty());
    assertThat(service.refund(orderId, "x")).isFalse();
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    assertThat(service.refund(orderId, "x")).isFalse();
    verify(repo, never()).insertRefund(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any(), any());
  }
}
