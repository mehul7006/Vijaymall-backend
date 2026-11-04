package com.vijay.mall.payment.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.domain.PaymentSession;
import com.vijay.mall.payment.infra.PaymentRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Public interface of the payment module. */
@Service
public class PaymentService {
  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final PaymentRepository repo;
  private final PaymentGatewayRegistry registry;
  private final ApplicationEventPublisher events;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;

  public PaymentService(PaymentRepository repo, PaymentGatewayRegistry registry, ApplicationEventPublisher events,
                        AuditService audit, Clock clock, TransactionTemplate tx) {
    this.tx = tx;
    this.repo = repo;
    this.registry = registry;
    this.events = events;
    this.audit = audit;
    this.clock = clock;
  }

  /** What the admin sees: the switch, and whether the method could actually work (ONLINE needs its provider keys). */
  public record AdminMethod(String code, boolean enabled, boolean configured) {}

  /** Methods customers may pick: switched on by the admin and, for ONLINE, set up with provider keys. */
  public List<PaymentRepository.Setting> enabledMethods() {
    return repo.settings().stream().filter(PaymentRepository.Setting::enabled).filter(s -> usable(s.code())).toList();
  }

  public void requireEnabled(String method) {
    if (!repo.enabled(method) || !usable(method)) throw ApiException.validation("paymentMethod", method + " is not available");
  }

  private boolean usable(String code) { return !"ONLINE".equals(code) || registry.onlineConfigured(); }

  /** Starts an online payment; returns empty for COD or a zero total. */
  @Transactional
  public Optional<PaymentSession> start(UUID orderId, String orderNo, long amountPaise, String method) {
    if (!"ONLINE".equals(method) || amountPaise <= 0) return Optional.empty();
    PaymentGateway g = registry.online();
    PaymentGateway.Created c = g.create(new PaymentGateway.CreateRequest(orderId, orderNo, amountPaise));
    repo.insert(UUID.randomUUID(), orderId, g.provider(), method, amountPaise, "CREATED", c.providerOrderId(), clock.instant());
    return Optional.ofNullable(c.session());
  }

  /** Payload for an order still waiting for payment (used when the app reopens the order). */
  public Optional<PaymentSession> sessionFor(UUID orderId) {
    return repo.latestForOrder(orderId).filter(p -> "CREATED".equals(p.status()))
        .map(p -> registry.byProvider(p.provider()).session(p.providerOrderId(), p.amountPaise()));
  }

  /** Called by the webhook endpoint. Safe to call twice with the same event. */
  @Transactional
  public void handleWebhook(String provider, String rawBody, Map<String, String> headers) {
    PaymentGateway g = registry.byProvider(provider);
    PaymentEvent ev;
    try {
      ev = g.parseWebhook(rawBody, headers);
    } catch (InvalidSignatureException e) {
      log.warn("Webhook rejected: bad signature provider={}", provider);
      throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_SIGNATURE", "Invalid signature");
    }
    if (!repo.recordEvent(g.provider(), ev.eventId(), ev.getClass().getSimpleName(), rawBody, clock.instant())) {
      log.info("Webhook duplicate ignored provider={} event={}", provider, ev.eventId());
      return;
    }
    log.info("Webhook accepted provider={} event={} type={}", provider, ev.eventId(), ev.getClass().getSimpleName());
    apply(g.provider(), ev);
  }

  private void apply(String provider, PaymentEvent ev) {
    switch (ev) {
      case PaymentEvent.Captured c -> repo.byProviderOrder(provider, c.providerOrderId()).ifPresent(p -> {
        if (!"CREATED".equals(p.status())) return;
        if (p.amountPaise() != c.amountPaise()) {
          log.error("Amount mismatch for payment {}: expected {} got {}", p.id(), p.amountPaise(), c.amountPaise());
          repo.setStatus(p.id(), "FAILED", c.providerRef(), "amount mismatch", clock.instant());
          return;
        }
        repo.setStatus(p.id(), "CAPTURED", c.providerRef(), null, clock.instant());
        log.info("Payment captured order={} ref={} amount={}", p.orderId(), c.providerRef(), c.amountPaise());
        events.publishEvent(new PaymentEvents.PaymentCaptured(p.orderId(), c.providerRef()));
      });
      case PaymentEvent.Failed f -> repo.byProviderOrder(provider, f.providerOrderId()).ifPresent(p -> {
        if (!"CREATED".equals(p.status())) return;
        repo.setStatus(p.id(), "FAILED", null, f.reason(), clock.instant());
        log.warn("Payment failed order={} reason={}", p.orderId(), f.reason());
        events.publishEvent(new PaymentEvents.PaymentFailed(p.orderId(), f.reason()));
      });
      case PaymentEvent.Ignored _ -> { }
    }
  }

  /** Refunds a captured payment. Returns true when a refund was issued. */
  @Transactional
  public boolean refund(UUID orderId, String reason) {
    Optional<PaymentRepository.Payment> pay = repo.latestForOrder(orderId).filter(p -> "CAPTURED".equals(p.status()));
    if (pay.isEmpty()) return false;
    PaymentRepository.Payment p = pay.get();
    String ref = registry.byProvider(p.provider()).refund(p.providerRef(), p.amountPaise(), reason);
    repo.insertRefund(p.id(), p.amountPaise(), "ISSUED", ref, reason, clock.instant());
    repo.setStatus(p.id(), "REFUNDED", null, null, clock.instant());
    log.info("Payment refunded order={} amount={} reason={}", orderId, p.amountPaise(), reason);
    return true;
  }

  /** Asks the provider about payments whose webhook never arrived. */
  @Scheduled(fixedDelayString = "PT5M")
  public void reconcile() {
    for (PaymentRepository.Payment p : repo.staleCreated(clock.instant().minus(5, ChronoUnit.MINUTES), 50)) {
      try {
        PaymentGateway g = registry.byProvider(p.provider());
        g.fetchStatus(p.providerOrderId()).ifPresent(ev -> tx.executeWithoutResult(t -> reconcileOne(g.provider(), ev)));
      } catch (RuntimeException e) {
        log.warn("Reconcile failed for payment {}: {}", p.id(), e.getMessage());
      }
    }
  }

  private void reconcileOne(String provider, PaymentEvent ev) {
    if (repo.recordEvent(provider, ev.eventId(), "Reconcile", "{}", clock.instant())) apply(provider, ev);
  }

  // ---------- admin ----------
  public List<PaymentRepository.Setting> allMethods() { return repo.settings(); }

  /** Both methods with their switch and whether they are set up, for the admin screen. */
  public List<AdminMethod> adminMethods() {
    return repo.settings().stream().map(s -> new AdminMethod(s.code(), s.enabled(), usable(s.code()))).toList();
  }

  /**
   * Switches methods on or off. Online payment cannot be switched on until the provider keys are set, and at least one
   * method must stay on so customers can always check out.
   */
  @Transactional
  public List<PaymentRepository.Setting> saveMethods(List<PaymentRepository.Setting> in, UUID actor) {
    Map<String, Boolean> result = new java.util.LinkedHashMap<>();
    repo.settings().forEach(s -> result.put(s.code(), s.enabled()));
    for (PaymentRepository.Setting s : in) {
      if (!List.of("COD", "ONLINE").contains(s.code())) throw ApiException.validation("code", "unknown method");
      if (s.enabled() && !usable(s.code())) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PAYMENT_NOT_CONFIGURED",
            "Online payment cannot be switched on until the payment provider keys are set");
      }
      result.put(s.code(), s.enabled());
    }
    if (result.values().stream().noneMatch(Boolean::booleanValue)) {
      throw ApiException.validation("methods", "keep at least one payment method on");
    }
    for (PaymentRepository.Setting s : in) repo.setEnabled(s.code(), s.enabled());
    audit.log(actor, "UPDATE", "payment_setting", null, in.toString());
    return repo.settings();
  }
}
