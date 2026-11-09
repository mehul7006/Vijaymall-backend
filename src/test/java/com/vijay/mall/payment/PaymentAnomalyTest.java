package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.payment.app.PaymentGatewayRegistry;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.payment.infra.PaymentRepository;
import com.vijay.mall.support.FakeGateway;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wrong-amount and duplicate online payments: refunded in full, never paying (or unpaying) the order. */
class PaymentAnomalyTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

  private PaymentRepository repo;
  private ApplicationEventPublisher events;
  private AuditService audit;
  private PaymentService service;
  private final UUID orderId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(PaymentRepository.class);
    events = mock(ApplicationEventPublisher.class);
    audit = mock(AuditService.class);
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(0, 0));
    service = new PaymentService(repo, new PaymentGatewayRegistry(List.of(new FakeGateway()), props), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.recordEvent(any(), any(), any(), any(), any())).thenReturn(true);
  }

  private void open(String status, String ref) {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(
        new PaymentRepository.Payment(paymentId, orderId, "fake", 5000, status, "fake_" + orderId, ref)));
  }

  private void capture(String eventId, long amount) {
    String body = "{\"eventId\":\"" + eventId + "\",\"type\":\"captured\",\"providerOrderId\":\"fake_" + orderId + "\",\"amount\":" + amount + "}";
    service.handleWebhook("fake", body, Map.of("x-fake-signature", "ok"));
  }

  @Test
  void aWrongAmountIsRefundedInFullAndTheOrderStaysPayable() {
    open("CREATED", null);
    capture("w1", 4000);
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(repo).insertRefund(paymentId, 4000, "PENDING", null, "amount mismatch", "pay_w1", NOW);
    verify(repo).insertAnomaly(paymentId, orderId, "MISMATCH", 5000, 4000, "pay_w1", NOW);
    verify(audit).log(eq(null), eq("PAYMENT_ANOMALY"), eq("payment"), eq(paymentId), any());
    verify(events).publishEvent(new PaymentEvents.PaymentAnomaly(orderId, "MISMATCH", 5000, 4000));
    verify(events, never()).publishEvent(any(PaymentEvents.PaymentCaptured.class));
  }

  @Test
  void aWrongAmountOnALegacyMismatchOrFailedPaymentIsHandledTheSameWay() {
    for (String status : new String[] {"MISMATCH", "FAILED"}) {
      open(status, "pay_old");
      capture("l-" + status, 6000);
    }
    verify(repo).insertAnomaly(paymentId, orderId, "MISMATCH", 5000, 6000, "pay_l-MISMATCH", NOW);
    verify(repo).insertAnomaly(paymentId, orderId, "MISMATCH", 5000, 6000, "pay_l-FAILED", NOW);
  }

  @Test
  void aCorrectCaptureAfterAMismatchIsAcceptedNormally() {
    open("CREATED", null);
    capture("w1", 4000);
    capture("w2", 5000);
    verify(repo).setStatus(paymentId, "CAPTURED", "pay_w2", null, NOW);
    verify(events).publishEvent(new PaymentEvents.PaymentCaptured(orderId, "pay_w2"));
    verify(repo, Mockito.times(1)).insertAnomaly(any(), any(), any(), anyLong(), anyLong(), any(), any());
  }

  @Test
  void aSecondPaymentOnAPaidOrderIsRefundedAndTheFirstIsKept() {
    open("CAPTURED", "pay_first");
    capture("d1", 5000);
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(repo).insertRefund(paymentId, 5000, "PENDING", null, "duplicate payment", "pay_d1", NOW);
    verify(repo).insertAnomaly(paymentId, orderId, "DUPLICATE", 5000, 5000, "pay_d1", NOW);
    verify(events).publishEvent(new PaymentEvents.PaymentAnomaly(orderId, "DUPLICATE", 5000, 5000));
    verify(events, never()).publishEvent(any(PaymentEvents.PaymentCaptured.class));
  }

  @Test
  void theSamePaymentReportedAgainUnderAnotherEventIsNotADuplicate() {
    open("CAPTURED", "pay_d2");
    capture("d2", 5000);
    verify(repo, never()).insertRefund(any(), anyLong(), any(), any(), any(), any(), any());
    verify(repo, never()).insertAnomaly(any(), any(), any(), anyLong(), anyLong(), any(), any());
  }

  @Test
  void aReplayedAnomalyDoesNotQueueASecondRefund() {
    open("CREATED", null);
    when(repo.anomalyRefundExists("pay_r1", "amount mismatch")).thenReturn(true);
    capture("r1", 4000);
    verify(repo, never()).insertRefund(any(), anyLong(), any(), any(), any(), any(), any());
    verify(repo, never()).insertAnomaly(any(), any(), any(), anyLong(), anyLong(), any(), any());
    verify(events, never()).publishEvent(any(PaymentEvents.PaymentAnomaly.class));
  }

  @Test
  void aReplayedWebhookEventIsStoppedByTheEventLog() {
    open("CREATED", null);
    when(repo.recordEvent(any(), eq("r2"), any(), any(), any())).thenReturn(false);
    capture("r2", 4000);
    verify(repo, never()).insertRefund(any(), anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void aProviderPaymentWithoutAReferenceCannotBeRefundedAutomatically() {
    var gw = new FakeGateway() {
      @Override public PaymentEvent parseWebhook(String raw, Map<String, String> h) {
        return new PaymentEvent.Captured("n1", "fake_" + orderId, null, Long.parseLong(raw));
      }
    };
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(0, 0));
    PaymentService s = new PaymentService(repo, new PaymentGatewayRegistry(List.of(gw), props), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    open("CAPTURED", "pay_first");
    s.handleWebhook("fake", "5000", Map.of());
    open("CREATED", null);
    s.handleWebhook("fake", "4000", Map.of());
    verify(repo, never()).insertRefund(any(), anyLong(), any(), any(), any(), any(), any());
    verify(repo, never()).insertAnomaly(any(), any(), any(), anyLong(), anyLong(), any(), any());
  }

  @Test
  void theCounterIsCountedByKindWhenMetricsAreWired() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ReflectionTestUtils.invokeMethod(service, "setMetrics", registry);
    open("CREATED", null);
    capture("m1", 1);
    open("CAPTURED", "pay_first");
    capture("m2", 5000);
    assertThat(registry.counter("mall.payments.anomalies", "kind", "MISMATCH").count()).isEqualTo(1);
    assertThat(registry.counter("mall.payments.anomalies", "kind", "DUPLICATE").count()).isEqualTo(1);
  }

  @Test
  void anomaliesArePagedWithTheSizeClampedAndNegativePagesFixed() {
    when(repo.anomalyPage(0, 20)).thenReturn(new Page<>(List.of(), 0, 20, 0));
    assertThat(service.anomalies(-3, 20).total()).isZero();
    verify(repo).anomalyPage(0, 20);
  }
}
