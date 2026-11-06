package com.vijay.mall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.app.PaymentGatewayRegistry;
import com.vijay.mall.payment.app.PaymentService;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.infra.CodGateway;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The parts of PaymentService and the registry not covered by PaymentServiceTest. */
class PaymentFlowsTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final PaymentRepository repo = mock(PaymentRepository.class);
  private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
  private final AuditService audit = mock(AuditService.class);
  private final FakeGateway fake = new FakeGateway();
  private final UUID orderId = UUID.randomUUID();
  private final UUID paymentId = UUID.randomUUID();
  private PaymentService service;
  private PaymentGatewayRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new PaymentGatewayRegistry(List.of(fake, new CodGateway()), props("fake"));
    service = new PaymentService(repo, registry, events, audit, Clock.fixed(NOW, ZoneOffset.UTC),
        new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.recordEvent(any(), any(), any(), any(), any())).thenReturn(true);
  }

  private static AppProps props(String online) {
    return new AppProps(null, null, null, null, new AppProps.Payment(online, 15, null, null, null, null), null);
  }

  private PaymentRepository.Payment payment(String status) {
    return new PaymentRepository.Payment(paymentId, orderId, "fake", 5000, status, "fake_" + orderId, "pay_1");
  }

  // ---------- methods ----------
  @Test
  void onlyEnabledMethodsAreOffered() {
    when(repo.settings()).thenReturn(List.of(new PaymentRepository.Setting("COD", true), new PaymentRepository.Setting("ONLINE", false)));
    assertThat(service.enabledMethods()).containsExactly(new PaymentRepository.Setting("COD", true));
    assertThat(service.allMethods()).hasSize(2);
  }

  @Test
  void requireEnabledRejectsDisabledMethods() {
    when(repo.enabled("COD")).thenReturn(true);
    service.requireEnabled("COD");
    assertThatThrownBy(() -> service.requireEnabled("ONLINE")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void saveMethodsStoresEachAndAuditsTheChange() {
    UUID admin = UUID.randomUUID();
    var in = List.of(new PaymentRepository.Setting("COD", true), new PaymentRepository.Setting("ONLINE", false));
    service.saveMethods(in, admin);
    verify(repo).setEnabled("COD", true);
    verify(repo).setEnabled("ONLINE", false);
    verify(audit).log(eq(admin), eq("UPDATE"), eq("payment_setting"), any(), anyString());
  }

  @Test
  void onlineIsHiddenAndRefusedWhileTheProviderIsNotConfigured() {
    PaymentGateway unconfigured = mock(PaymentGateway.class);
    when(unconfigured.provider()).thenReturn("fake");
    when(unconfigured.configured()).thenReturn(false);
    var svc = new PaymentService(repo, new PaymentGatewayRegistry(List.of(unconfigured), props("fake")), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.settings()).thenReturn(List.of(new PaymentRepository.Setting("COD", true), new PaymentRepository.Setting("ONLINE", true)));
    when(repo.enabled("ONLINE")).thenReturn(true);
    assertThat(svc.enabledMethods()).containsExactly(new PaymentRepository.Setting("COD", true));
    assertThatThrownBy(() -> svc.requireEnabled("ONLINE")).isInstanceOf(ApiException.class);
    assertThat(svc.adminMethods()).containsExactly(new PaymentService.AdminMethod("COD", true, true),
        new PaymentService.AdminMethod("ONLINE", true, false));
    assertThatThrownBy(() -> svc.saveMethods(List.of(new PaymentRepository.Setting("ONLINE", true)), UUID.randomUUID()))
        .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_NOT_CONFIGURED"));
    verify(repo, never()).setEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void atLeastOneMethodMustStayOn() {
    when(repo.settings()).thenReturn(List.of(new PaymentRepository.Setting("COD", true), new PaymentRepository.Setting("ONLINE", false)));
    assertThatThrownBy(() -> service.saveMethods(List.of(new PaymentRepository.Setting("COD", false)), UUID.randomUUID()))
        .isInstanceOf(ApiException.class);
    verify(repo, never()).setEnabled(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  // ---------- start / session ----------
  @Test
  void startRecordsACreatedPaymentAndReturnsTheSession() {
    var session = service.start(orderId, "VM-1", 5000, "ONLINE");
    assertThat(session).isPresent();
    verify(repo).insert(any(), eq(orderId), eq("fake"), eq("ONLINE"), eq(5000L), eq("CREATED"), eq("fake_" + orderId), eq(NOW));
  }

  @Test
  void startWithoutConfiguredProviderFailsClearly() {
    var broken = new PaymentService(repo, new PaymentGatewayRegistry(List.of(new CodGateway()), props("nope")), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    assertThatThrownBy(() -> broken.start(orderId, "VM-1", 5000, "ONLINE")).isInstanceOfSatisfying(ApiException.class,
        e -> {
          assertThat(e.code()).isEqualTo("PAYMENT_NOT_CONFIGURED");
          assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        });
  }

  @Test
  void sessionIsRebuiltOnlyForPaymentsStillWaiting() {
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CREATED")));
    assertThat(service.sessionFor(orderId)).isPresent();
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CAPTURED")));
    assertThat(service.sessionFor(orderId)).isEmpty();
    when(repo.latestForOrder(orderId)).thenReturn(Optional.empty());
    assertThat(service.sessionFor(orderId)).isEmpty();
  }

  // ---------- webhook edge cases ----------
  private static String body(String id, String type, String providerOrderId, long amount) {
    return "{\"eventId\":\"" + id + "\",\"type\":\"" + type + "\",\"providerOrderId\":\"" + providerOrderId + "\",\"amount\":" + amount + "}";
  }

  @Test
  void ignoredEventTypesChangeNothing() {
    service.handleWebhook("fake", body("e1", "refunded", "x", 0), java.util.Map.of("x-fake-signature", "ok"));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
    org.mockito.Mockito.verifyNoInteractions(events);
  }

  @Test
  void eventForUnknownProviderOrderIsAcceptedAndDoesNothing() {
    when(repo.byProviderOrder("fake", "ghost")).thenReturn(Optional.empty());
    service.handleWebhook("fake", body("e2", "captured", "ghost", 5000), java.util.Map.of("x-fake-signature", "ok"));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
  }

  @Test
  void failureAfterCaptureIsIgnored() {
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CAPTURED")));
    service.handleWebhook("fake", body("e3", "failed", "fake_" + orderId, 0), java.util.Map.of("x-fake-signature", "ok"));
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
  }

  // ---------- refund ----------
  @Test
  void refundCallsTheProviderStoresItAndMarksRefunded() {
    when(repo.latestForOrder(orderId)).thenReturn(Optional.of(payment("CAPTURED")));
    assertThat(service.refund(orderId, "cancelled")).isTrue();
    assertThat(fake.refunds).containsExactly("pay_1:5000");
    verify(repo).insertRefund(paymentId, 5000, "ISSUED", "refund_pay_1", "cancelled", NOW);
    verify(repo).setStatus(paymentId, "REFUNDED", null, null, NOW);
  }

  // ---------- reconcile ----------
  @Test
  void reconcileAppliesWhatTheProviderReports() {
    var gw = mock(PaymentGateway.class);
    when(gw.provider()).thenReturn("fake");
    when(gw.fetchStatus("fake_" + orderId)).thenReturn(Optional.of(new PaymentEvent.Captured("recon:p", "fake_" + orderId, "pay_9", 5000)));
    var svc = new PaymentService(repo, new PaymentGatewayRegistry(List.of(gw), props("fake")), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.staleCreated(NOW.minusSeconds(300), 50)).thenReturn(List.of(payment("CREATED")));
    when(repo.byProviderOrder("fake", "fake_" + orderId)).thenReturn(Optional.of(payment("CREATED")));
    svc.reconcile();
    verify(repo).setStatus(paymentId, "CAPTURED", "pay_9", null, NOW);
    verify(events).publishEvent(new PaymentEvents.PaymentCaptured(orderId, "pay_9"));
  }

  @Test
  void reconcileSkipsAnEventItAlreadySaw() {
    var gw = mock(PaymentGateway.class);
    when(gw.provider()).thenReturn("fake");
    when(gw.fetchStatus(anyString())).thenReturn(Optional.of(new PaymentEvent.Captured("recon:p", "o", "pay_9", 5000)));
    var svc = new PaymentService(repo, new PaymentGatewayRegistry(List.of(gw), props("fake")), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    when(repo.staleCreated(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(payment("CREATED")));
    when(repo.recordEvent(any(), any(), any(), any(), any())).thenReturn(false);
    svc.reconcile();
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
  }

  @Test
  void reconcileNothingToSayLeavesPaymentAlone() {
    when(repo.staleCreated(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(payment("CREATED")));
    service.reconcile(); // FakeGateway.fetchStatus returns empty
    verify(repo, never()).setStatus(any(), any(), any(), any(), any());
  }

  @Test
  void oneBrokenProviderDoesNotStopTheRest() {
    var gw = mock(PaymentGateway.class);
    when(gw.provider()).thenReturn("fake");
    doThrow(new IllegalStateException("provider down")).when(gw).fetchStatus(anyString());
    var svc = new PaymentService(repo, new PaymentGatewayRegistry(List.of(gw), props("fake")), events, audit,
        Clock.fixed(NOW, ZoneOffset.UTC), new TransactionTemplate(mock(PlatformTransactionManager.class)));
    var other = new PaymentRepository.Payment(UUID.randomUUID(), UUID.randomUUID(), "fake", 1, "CREATED", "o2", null);
    when(repo.staleCreated(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(payment("CREATED"), other));
    svc.reconcile();
    verify(gw).fetchStatus("fake_" + orderId);
    verify(gw).fetchStatus("o2");
  }

  // ---------- registry / COD ----------
  @Test
  void registryLooksUpCaseInsensitivelyAndRejectsUnknownOrNull() {
    assertThat(registry.byProvider("FAKE")).isSameAs(fake);
    assertThat(registry.online()).isSameAs(fake);
    assertThatThrownBy(() -> registry.byProvider("paypal")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> registry.byProvider(null)).isInstanceOf(ApiException.class);
  }

  @Test
  void codGatewayDoesNothingOnline() {
    var cod = new CodGateway();
    assertThat(cod.provider()).isEqualTo("cod");
    assertThat(cod.create(new PaymentGateway.CreateRequest(orderId, "VM-1", 100))).isEqualTo(new PaymentGateway.Created(null, null));
    assertThat(cod.session("x", 1)).isNull();
    assertThat(cod.refund("r", 1, "why")).isNull();
    assertThat(cod.fetchStatus("x")).isEmpty();
    assertThatThrownBy(() -> cod.parseWebhook("{}", java.util.Map.of()))
        .isInstanceOf(com.vijay.mall.payment.domain.InvalidSignatureException.class);
  }
}
