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
import java.time.Instant;
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
  private com.vijay.mall.common.cache.AppCaches caches;

  /** Optional (plain unit tests run without it): which methods are switched on is read for every checkout, so it is cached. */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setCaches(com.vijay.mall.common.cache.AppCaches caches) { this.caches = caches; }

  private List<PaymentRepository.Setting> switches() {
    return caches == null ? repo.settings() : caches.get(com.vijay.mall.common.cache.AppCaches.Name.PAYMENT_METHODS, "all", repo::settings);
  }

  public List<PaymentRepository.Setting> enabledMethods() {
    return switches().stream().filter(PaymentRepository.Setting::enabled).filter(s -> usable(s.code())).toList();
  }

  public void requireEnabled(String method) {
    boolean on = caches == null ? repo.enabled(method) : switches().stream().anyMatch(s -> s.code().equals(method) && s.enabled());
    if (!on || !usable(method)) throw ApiException.validation("paymentMethod", method + " is not available");
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
        // A failed attempt does not close the provider order (the customer can retry), so a capture is still welcome
        // after one. FAILED is only left over from older versions that closed the payment on the first failure.
        if (!"CREATED".equals(p.status()) && !"FAILED".equals(p.status())) return;
        if (p.amountPaise() != c.amountPaise()) {
          log.error("Amount mismatch for payment {}: expected {} got {}", p.id(), p.amountPaise(), c.amountPaise());
          // The money did arrive, but not the right amount: keep the provider reference and give all of it back.
          repo.setStatus(p.id(), "MISMATCH", c.providerRef(), "amount mismatch", clock.instant());
          repo.insertRefund(p.id(), c.amountPaise(), "PENDING", null, "amount mismatch", clock.instant());
          afterCommit(() -> sendQueuedRefunds(AFTER_COMMIT_REFUNDS));
          return;
        }
        repo.setStatus(p.id(), "CAPTURED", c.providerRef(), null, clock.instant());
        log.info("Payment captured order={} ref={} amount={}", p.orderId(), c.providerRef(), c.amountPaise());
        events.publishEvent(new PaymentEvents.PaymentCaptured(p.orderId(), c.providerRef()));
      });
      case PaymentEvent.Failed f -> repo.byProviderOrder(provider, f.providerOrderId()).ifPresent(p -> {
        if (!"CREATED".equals(p.status())) return;
        // One failed attempt is not the end: the customer may retry on the same provider order. Remember why and keep
        // waiting; an order nobody pays for is cancelled by the expiry job.
        repo.setStatus(p.id(), "CREATED", null, f.reason(), clock.instant());
        log.warn("Payment attempt failed order={} reason={}", p.orderId(), f.reason());
      });
      case PaymentEvent.Ignored _ -> { }
    }
  }

  public static final int MAX_REFUND_TRIES = 10;
  static final int AFTER_COMMIT_REFUNDS = 5;

  /**
   * Queues a refund of a captured payment and returns true. The provider is called after the surrounding transaction
   * commits (and again by a job if that call fails), so a cancel never waits for, or fails with, the provider.
   */
  @Transactional
  public boolean refund(UUID orderId, String reason) {
    Optional<PaymentRepository.Payment> pay = repo.latestForOrder(orderId).filter(p -> "CAPTURED".equals(p.status()));
    if (pay.isEmpty()) return false;
    PaymentRepository.Payment p = pay.get();
    repo.insertRefund(p.id(), p.amountPaise(), "PENDING", null, reason, clock.instant());
    repo.setStatus(p.id(), "REFUNDED", null, null, clock.instant());
    log.info("Payment refund queued order={} amount={} reason={}", orderId, p.amountPaise(), reason);
    afterCommit(() -> sendQueuedRefunds(AFTER_COMMIT_REFUNDS));
    return true;
  }

  /**
   * Runs {@code task} once the surrounding transaction has committed. At that moment Spring still has the finished
   * transaction bound to the thread, so database work started there would join it and never be saved (or fail). The
   * task therefore runs with that transaction suspended, so each of its steps gets its own. A failure is logged, never
   * thrown: the business change is already committed, and the 30-second job will pick the work up again.
   */
  /**
   * Queues a refund of part of a captured online payment (for example goods sent back after delivery) and returns true.
   * Returns false when the order was not paid online. Never pays back more than was paid: the total already refunded or
   * queued for this payment plus this amount must stay within it.
   */
  @Transactional
  public boolean refundPartial(UUID orderId, long amountPaise, String reason) {
    if (amountPaise <= 0) throw ApiException.validation("amount", "must be positive");
    Optional<PaymentRepository.Payment> pay = repo.latestForOrder(orderId).filter(p -> "CAPTURED".equals(p.status()));
    if (pay.isEmpty()) return false;
    PaymentRepository.Payment p = pay.get();
    long committed = repo.refundedSoFar(p.id());
    if (committed + amountPaise > p.amountPaise()) {
      throw ApiException.conflict("REFUND_EXCEEDS_PAYMENT", "More than the customer paid", Map.of("paidPaise", p.amountPaise(),
          "alreadyRefundedPaise", committed, "requestedPaise", amountPaise));
    }
    repo.insertRefund(p.id(), amountPaise, "PENDING", null, reason, clock.instant());
    if (committed + amountPaise == p.amountPaise()) repo.setStatus(p.id(), "REFUNDED", null, null, clock.instant());
    log.info("Partial refund queued order={} amount={} reason={}", orderId, amountPaise, reason);
    afterCommit(() -> sendQueuedRefunds(AFTER_COMMIT_REFUNDS));
    return true;
  }

  /** The admin's view of refunds (status PENDING, PROCESSING, ISSUED or FAILED; blank for all), newest first. */
  public com.vijay.mall.common.Page<PaymentRepository.RefundRow> refunds(String status, int page, int size) {
    String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
    if (s != null && !List.of("PENDING", "PROCESSING", "ISSUED", "FAILED").contains(s)) {
      throw ApiException.validation("status", "PENDING, PROCESSING, ISSUED or FAILED");
    }
    return repo.refundPage(s, Math.max(0, page), com.vijay.mall.common.Page.clampSize(size));
  }

  /** Gives a FAILED refund a fresh run of tries (after the cause, such as a provider outage, has been dealt with). */
  @Transactional
  public void retryRefund(UUID refundId, UUID actor) {
    if (!repo.requeueFailedRefund(refundId, clock.instant())) {
      throw ApiException.conflict("REFUND_NOT_FAILED", "Only a failed refund can be retried", Map.of());
    }
    audit.log(actor, "REFUND_RETRY", "refund", refundId, null);
    afterCommit(() -> sendQueuedRefunds(AFTER_COMMIT_REFUNDS));
  }

  private void afterCommit(Runnable task) {
    Runnable safe = () -> {
      try {
        var outsideAnyTransaction = new TransactionTemplate(tx.getTransactionManager());
        outsideAnyTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outsideAnyTransaction.executeWithoutResult(s -> task.run());
      } catch (RuntimeException e) {
        log.warn("Work after commit failed, the scheduled job will retry: {}", e.getMessage());
      }
    };
    if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
          new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() { safe.run(); }
          });
    } else {
      safe.run();
    }
  }

  /** Sends queued refunds to the provider. Runs after a commit and every 30 seconds, never inside a transaction. */
  @Scheduled(fixedDelayString = "PT30S")
  public void sendQueuedRefunds() { sendQueuedRefunds(50); }

  /** After a commit only a few are sent, so one customer's cancel never waits on a long queue; the scheduled run takes the rest. */
  void sendQueuedRefunds(int limit) {
    Instant now = clock.instant();
    repo.requeueStuckRefunds(now.minus(10, ChronoUnit.MINUTES), now);
    for (PaymentRepository.PendingRefund r : repo.pendingRefunds(limit)) {
      if (!repo.claimRefund(r.id(), clock.instant())) continue; // another server has it
      try {
        String ref = registry.byProvider(r.provider()).refund(r.providerRef(), r.amountPaise(), r.reason());
        repo.refundIssued(r.id(), ref, clock.instant());
        log.info("Refund sent payment={} amount={} ref={}", r.paymentId(), r.amountPaise(), ref);
      } catch (RuntimeException e) {
        repo.refundFailed(r.id(), e.getMessage(), MAX_REFUND_TRIES, clock.instant());
        log.warn("Refund failed payment={} attempt={} : {}", r.paymentId(), r.attempts() + 1, e.getMessage());
      }
    }
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

  private com.vijay.mall.common.live.LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLive(com.vijay.mall.common.live.LiveEvents live) { this.live = live; }

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
    if (live != null) live.area(com.vijay.mall.common.live.CatalogChanged.PAYMENT_METHODS);
    audit.log(actor, "UPDATE", "payment_setting", null, in.toString());
    return repo.settings();
  }
}
