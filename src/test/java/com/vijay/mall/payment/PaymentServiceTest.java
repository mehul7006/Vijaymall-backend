package com.vijay.mall.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
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
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentServiceTest {
  private PaymentRepository repo;
  private ApplicationEventPublisher events;
  private FakeGateway gateway;
  private PaymentService service;
  private final UUID orderId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    repo = mock(PaymentRepository.class);
    events = mock(ApplicationEventPublisher.class);
    gateway = new FakeGateway();
    AppProps props = new AppProps(null, null, null, null,
        new AppProps.Payment("fake", 15, null, null, null, null), new AppProps.Delivery(0, 0));
    PaymentGatewayRegistry registry = new PaymentGatewayRegistry(List.of(gateway), props);
    Clock clock = Clock.fixed(Instant.parse("2026-11-01T10:00:00Z"), ZoneOffset.UTC);
    service = new PaymentService(repo, registry, events, mock(AuditService.class), clock,
        new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.recordEvent(any(), any(), any(), any(), any())).thenReturn(true);
  }

  private PaymentRepository.Payment payment(String status, long amount) {
    return new PaymentRepository.Payment(paymentId, orderId, "fake", amount, status, "fake_" + orderId, "pay_1");
  }

  private static String body(String id, String type, String providerOrderId, long amount) {
    return "{\"eventId\":\"" + id + "\",\"type\":\"" + type + "\",\"providerOrderId\":\"" + providerOrderId + "\",\"amount\":" + amount + "}";
  }

  @Test
  void startOnlyCreatesAPaymentForOnlineOrdersWithAmount() {
    assertTrue(service.start(orderId, "VSM-1", 5000, "COD").isEmpty());
    assertTrue(service.start(orderId, "VSM-1", 0, "ONLINE").isEmpty());
    var session = service.start(orderId, "VSM-1", 5000, "ONLINE");
    assertTrue(session.isPresent());
    assertEquals("WEB", session.get().type());
    verify(repo).insert(any(), eq(orderId), eq("fake"), eq("ONLINE"), eq(5000L), eq("CREATED"), eq("fake_" + orderId), any());
  }

  @Test
  void badSignatureIsRejectedAndNothingIsSaved() {
    ApiException e = assertThrows(ApiException.class,
        () -> service.handleWebhook("fake", body("e1", "captured", "x", 1), Map.of("x-fake-signature", "nope")));
    assertEquals("BAD_SIGNATURE", e.code());
    assertEquals(400, e.status().value());
    verify(repo, never()).recordEvent(any(), any(), any(), any(), any());
  }

  @Test
  void capturedWithMatchingAmountMarksPaymentAndPublishesEvent() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    service.handleWebhook("fake", body("e1", "captured", "fake_" + orderId, 5000), Map.of("x-fake-signature", "ok"));
    verify(repo).setStatus(eq(paymentId), eq("CAPTURED"), eq("pay_e1"), any(), any());
    ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
    verify(events).publishEvent(ev.capture());
    assertEquals(new PaymentEvents.PaymentCaptured(orderId, "pay_e1"), ev.getValue());
  }

  @Test
  void capturedWithWrongAmountIsNotAccepted() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    service.handleWebhook("fake", body("e2", "captured", "fake_" + orderId, 4999), Map.of("x-fake-signature", "ok"));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(repo).insertRefund(eq(paymentId), eq(4999L), eq("PENDING"), any(), eq("amount mismatch"), eq("pay_e2"), any());
    verify(events, never()).publishEvent(any(PaymentEvents.PaymentCaptured.class));
  }

  @Test
  void duplicateEventIsIgnored() {
    when(repo.recordEvent(any(), eq("e3"), any(), any(), any())).thenReturn(false);
    service.handleWebhook("fake", body("e3", "captured", "fake_" + orderId, 5000), Map.of("x-fake-signature", "ok"));
    verify(repo, never()).byProviderOrder(any(), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void alreadyCapturedPaymentIsNotCapturedTwice() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CAPTURED", 5000)));
    service.handleWebhook("fake", body("e4", "captured", "fake_" + orderId, 5000), Map.of("x-fake-signature", "ok"));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(events, never()).publishEvent(any(PaymentEvents.PaymentCaptured.class));
    verify(repo).insertRefund(eq(paymentId), eq(5000L), eq("PENDING"), any(), eq("duplicate payment"), eq("pay_e4"), any());
  }

  @Test
  void failedAttemptKeepsThePaymentOpenForARetry() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    service.handleWebhook("fake", body("e5", "failed", "fake_" + orderId, 0), Map.of("x-fake-signature", "ok"));
    verify(repo).setStatus(eq(paymentId), eq("CREATED"), any(), eq("declined"), any());
    verify(events, never()).publishEvent(any(Object.class));
  }

  @Test
  void aCaptureAfterAFailedAttemptIsStillHonoured() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    service.handleWebhook("fake", body("e5", "failed", "fake_" + orderId, 0), Map.of("x-fake-signature", "ok"));
    service.handleWebhook("fake", body("e6", "captured", "fake_" + orderId, 5000), Map.of("x-fake-signature", "ok"));
    verify(events).publishEvent(new PaymentEvents.PaymentCaptured(orderId, "pay_e6"));
  }

  @Test
  void aCaptureOfTheWrongAmountIsQueuedForRefund() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    service.handleWebhook("fake", body("e7", "captured", "fake_" + orderId, 4900), Map.of("x-fake-signature", "ok"));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    verify(repo).insertRefund(eq(paymentId), eq(4900L), eq("PENDING"), any(), eq("amount mismatch"), eq("pay_e7"), any());
    verify(events, never()).publishEvent(any(PaymentEvents.PaymentCaptured.class));
  }

  @Test
  void refundOnlyWhenCaptured() {
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CREATED", 5000)));
    assertFalse(service.refund(orderId, "x"));
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CAPTURED", 5000)));
    assertTrue(service.refund(orderId, "cancelled"));
    assertEquals(List.of(), gateway.refunds, "the provider is not called inside the cancel");
    verify(repo).insertRefund(eq(paymentId), eq(5000L), eq("PENDING"), any(), eq("cancelled"), any());
    verify(repo).setStatus(eq(paymentId), eq("REFUNDED"), any(), any(), any());
  }

  @Test
  void unknownProviderIs404() {
    assertEquals(404, assertThrows(ApiException.class, () -> service.handleWebhook("nope", "{}", Map.of())).status().value());
  }

  @Test
  void settingsRejectUnknownCodes() {
    assertThrows(ApiException.class, () -> service.saveMethods(List.of(new PaymentRepository.Setting("BITCOIN", true)), UUID.randomUUID()));
    service.saveMethods(List.of(new PaymentRepository.Setting("COD", true), new PaymentRepository.Setting("ONLINE", true)), UUID.randomUUID());
    verify(repo).setEnabled("ONLINE", true);
  }

  @Test
  void aSaveMustNameEveryMethodExactlyOnce() {
    UUID admin = UUID.randomUUID();
    for (var in : List.of(List.<PaymentRepository.Setting>of(), List.of(new PaymentRepository.Setting("COD", true)))) {
      assertEquals(Map.of("fields", Map.of("methods", "must list every method")), assertThrows(ApiException.class, () -> service.saveMethods(in, admin)).details());
    }
    var twice = List.of(new PaymentRepository.Setting("COD", true), new PaymentRepository.Setting("COD", false));
    assertEquals(Map.of("fields", Map.of("methods", "duplicate code \"COD\"")), assertThrows(ApiException.class, () -> service.saveMethods(twice, admin)).details());
    verify(repo, never()).setEnabled(anyString(), anyBoolean());
  }
}
