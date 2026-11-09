package com.vijay.mall.payment.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.common.metrics.Metrics;
import com.vijay.mall.payment.domain.InvalidSignatureException;
import com.vijay.mall.payment.domain.PaymentEvent;
import com.vijay.mall.payment.domain.PaymentEvents;
import com.vijay.mall.payment.domain.PaymentGateway;
import com.vijay.mall.payment.domain.PaymentSession;
import com.vijay.mall.payment.infra.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

  /** Creates the service. */
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

  /** The cache registry that keeps the payment-method switches in memory; null in plain unit tests. */
  private AppCaches caches;

  /** Optional (plain unit tests run without it): which methods are switched on is read for every checkout, so it is cached. */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  private List<PaymentRepository.Setting> switches() {
    return caches == null ? repo.settings() : caches.get(AppCaches.Name.PAYMENT_METHODS, "all", repo::settings);
  }

  /**
   * The methods customers may pick: switched on by the admin and, for {@code ONLINE}, set up with provider keys. Cached.
   */
  public List<PaymentRepository.Setting> enabledMethods() {
    return switches().stream().filter(PaymentRepository.Setting::enabled).filter(s -> usable(s.code())).toList();
  }

  /** Fails with a validation error on {@code paymentMethod} when the method is switched off or not usable. */
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
      case PaymentEvent.Captured c -> repo.byProviderOrder(provider, c.providerOrderId()).ifPresent(p -> onCaptured(p, c));
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

  /** Refund reason of money received in the wrong amount. */
  public static final String REASON_MISMATCH = "amount mismatch";
  /** Refund reason of a second payment on an order that is already paid. */
  public static final String REASON_DUPLICATE = "duplicate payment";
  /** Anomaly kinds, as stored and as the metric tag. */
  public static final String KIND_MISMATCH = "MISMATCH";
  /** Anomaly kind: a second payment arrived on an order that is already paid. */
  public static final String KIND_DUPLICATE = "DUPLICATE";

  /**
   * A provider reported a capture. The right amount on a payment that is still open pays the order. Anything else is an
   * anomaly: the money is refunded in full and the first valid payment (or the open order) is left exactly as it was.
   */
  private void onCaptured(PaymentRepository.Payment p, PaymentEvent.Captured c) {
    // A failed attempt does not close the provider order (the customer can retry), so a capture is still welcome after
    // one. MISMATCH and FAILED are only left over from older versions that closed the payment on the first problem.
    boolean open = "CREATED".equals(p.status()) || "FAILED".equals(p.status()) || "MISMATCH".equals(p.status());
    if (!open) {
      // The same payment reported again (another event type) is nothing new; a different payment id is a second payment.
      if (c.providerRef() == null || c.providerRef().equals(p.providerRef())) return;
      anomaly(p, c, KIND_DUPLICATE, REASON_DUPLICATE);
      return;
    }
    if (p.amountPaise() != c.amountPaise()) {
      anomaly(p, c, KIND_MISMATCH, REASON_MISMATCH);
      return;
    }
    repo.setStatus(p.id(), "CAPTURED", c.providerRef(), null, clock.instant());
    log.info("Payment captured order={} ref={} amount={}", p.orderId(), c.providerRef(), c.amountPaise());
    events.publishEvent(new PaymentEvents.PaymentCaptured(p.orderId(), c.providerRef()));
  }

  private MeterRegistry metrics;

  /** Optional (plain unit tests run without it): counts wrong-amount and duplicate payments by kind. */
  @Autowired(required = false)
  void setMetrics(MeterRegistry metrics) { this.metrics = metrics; }

  /**
   * Handles money that must not stay with the shop: queues a full refund of exactly that provider payment, records and
   * audits the anomaly, counts it and lets the order module tell the customer and the staff. Does nothing when this
   * provider payment was already dealt with (a replayed webhook) or has no reference to refund.
   */
  private void anomaly(PaymentRepository.Payment p, PaymentEvent.Captured c, String kind, String reason) {
    if (c.providerRef() == null || repo.anomalyRefundExists(c.providerRef(), reason)) {
      log.warn("Payment anomaly skipped kind={} payment={} ref={}", kind, p.id(), c.providerRef());
      return;
    }
    Instant now = clock.instant();
    repo.insertRefund(p.id(), c.amountPaise(), "PENDING", null, reason, c.providerRef(), now);
    repo.insertAnomaly(p.id(), p.orderId(), kind, p.amountPaise(), c.amountPaise(), c.providerRef(), now);
    audit.log(null, "PAYMENT_ANOMALY", "payment", p.id(), kind + " expectedPaise=" + p.amountPaise() + " receivedPaise="
        + c.amountPaise() + " ref=" + c.providerRef());
    Metrics.count(metrics, "mall.payments.anomalies", "Wrong-amount and duplicate online payments, by kind", "kind", kind, 1);
    log.warn("event=payment-anomaly kind={} orderId={} paymentId={} expectedPaise={} receivedPaise={} providerRef={}", kind,
        p.orderId(), p.id(), p.amountPaise(), c.amountPaise(), c.providerRef());
    events.publishEvent(new PaymentEvents.PaymentAnomaly(p.orderId(), kind, p.amountPaise(), c.amountPaise()));
    afterCommit(() -> sendQueuedRefunds(AFTER_COMMIT_REFUNDS));
  }

  /** The admin's list of wrong-amount and duplicate payments, newest first. */
  public Page<PaymentRepository.AnomalyRow> anomalies(int page, int size) {
    return repo.anomalyPage(Math.max(0, page), Page.clampSize(size));
  }

  /** How many times a refund is sent to the provider before it is marked {@code FAILED} and needs a person. */
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
  public Page<PaymentRepository.RefundRow> refunds(String status, int page, int size) {
    String s = status == null || status.isBlank() ? null : status.trim().toUpperCase();
    if (s != null && !List.of("PENDING", "PROCESSING", "ISSUED", "FAILED").contains(s)) {
      throw ApiException.validation("status", "PENDING, PROCESSING, ISSUED or FAILED");
    }
    return repo.refundPage(s, Math.max(0, page), Page.clampSize(size));
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

  /**
   * Runs {@code task} once the surrounding transaction has committed. At that moment Spring still has the finished
   * transaction bound to the thread, so database work started there would join it and never be saved (or fail). The
   * task therefore runs with that transaction suspended, so each of its steps gets its own. A failure is logged, never
   * thrown: the business change is already committed, and the 30-second job will pick the work up again.
   */
  private void afterCommit(Runnable task) {
    Runnable safe = () -> {
      try {
        var outsideAnyTransaction = new TransactionTemplate(tx.getTransactionManager());
        outsideAnyTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outsideAnyTransaction.executeWithoutResult(s -> task.run());
      } catch (RuntimeException e) {
        log.warn("Work after commit failed, the scheduled job will retry: {}", e.getMessage());
      }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
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
        PaymentGateway gateway = registry.byProvider(r.provider());
        // A refund that was tried before may have gone through while its answer was lost: look first, never send it twice.
        String ref = r.attempts() > 0 ? gateway.findRefund(r.providerRef(), r.id().toString()).orElse(null) : null;
        if (ref != null) log.info("Refund already at the provider payment={} ref={}", r.paymentId(), ref);
        else ref = gateway.refund(r.providerRef(), r.amountPaise(), r.reason(), r.id().toString());
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
  /** The payment methods that exist; a save must name each of them once. */
  static final Set<String> METHOD_CODES = Set.of("COD", "ONLINE");

  /** Every method with its switch, read straight from the database. */
  public List<PaymentRepository.Setting> allMethods() { return repo.settings(); }

  private LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  /** Both methods with their switch and whether they are set up, for the admin screen. */
  public List<AdminMethod> adminMethods() {
    return repo.settings().stream().map(s -> new AdminMethod(s.code(), s.enabled(), usable(s.code()))).toList();
  }

  /**
   * Switches methods on or off. Online payment cannot be switched on until the provider keys are set, and at least one
   * method must stay on so customers can always check out. The list must name every method (COD and ONLINE) exactly once.
   *
   * @throws ApiException 400 {@code fields.methods} when the list is empty, misses a method or repeats one
   */
  @Transactional
  public List<PaymentRepository.Setting> saveMethods(List<PaymentRepository.Setting> in, UUID actor) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    repo.settings().forEach(s -> result.put(s.code(), s.enabled()));
    Set<String> named = new HashSet<>();
    for (PaymentRepository.Setting s : in) {
      if (!METHOD_CODES.contains(s.code())) throw ApiException.validation("code", "unknown method");
      if (!named.add(s.code())) throw ApiException.validation("methods", "duplicate code \"" + s.code() + "\"");
    }
    if (!named.equals(METHOD_CODES)) throw ApiException.validation("methods", "must list every method");
    for (PaymentRepository.Setting s : in) {
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
    if (live != null) live.area(CatalogChanged.PAYMENT_METHODS);
    audit.log(actor, "UPDATE", "payment_setting", null, in.toString());
    return repo.settings();
  }
}
