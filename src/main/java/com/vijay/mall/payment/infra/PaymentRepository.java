package com.vijay.mall.payment.infra;

import com.vijay.mall.common.Page;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores payments, handled provider events, refunds, payment anomalies and the payment method switches. */
@Repository
public class PaymentRepository {
  /**
   * A payment as stored. Money is in paise.
   * @param provider the gateway name, for example {@code razorpay}
   * @param status {@code CREATED}, {@code CAPTURED}, {@code REFUNDED}, or an older failed/mismatch state
   * @param providerOrderId the provider's order id
   * @param providerRef the provider's payment id once captured
   */
  public record Payment(UUID id, UUID orderId, String provider, long amountPaise, String status,
                        String providerOrderId, String providerRef) {}

  /** A wrong-amount or duplicate payment, as the admin sees it. kind is MISMATCH or DUPLICATE. */
  public record AnomalyRow(UUID id, UUID paymentId, UUID orderId, String kind, long expectedPaise, long receivedPaise,
                           String providerRef, Instant createdAt) {}

  /**
   * Whether a payment method is switched on.
   * @param code {@code COD} or {@code ONLINE}
   */
  public record Setting(String code, boolean enabled) {}

  /** A refund waiting to be sent to the provider. */
  public record PendingRefund(UUID id, UUID paymentId, String provider, String providerRef, long amountPaise, String reason,
                              int attempts) {}

  private final PaymentJpa payments;
  private final PaymentEventJpa events;
  private final RefundJpa refunds;
  private final PaymentSettingJpa settings;
  private final PaymentAnomalyJpa anomalies;

  PaymentRepository(PaymentJpa payments, PaymentEventJpa events, RefundJpa refunds, PaymentSettingJpa settings,
                    PaymentAnomalyJpa anomalies) {
    this.anomalies = anomalies;
    this.payments = payments;
    this.events = events;
    this.refunds = refunds;
    this.settings = settings;
  }

  private static Payment toDomain(PaymentEntity e) {
    return new Payment(e.id, e.orderId, e.provider, e.amountPaise, e.status, e.providerOrderId, e.providerRef);
  }

  /** Inserts a new payment (normally with status {@code CREATED}) that belongs to no money account. */
  @Transactional
  public void insert(UUID id, UUID orderId, String provider, String method, long amount, String status,
                     String providerOrderId, Instant now) {
    insert(id, orderId, provider, method, amount, status, providerOrderId, now, null);
  }

  /** Inserts a new payment received in the given money account ({@code null} = unassigned). */
  @Transactional
  public void insert(UUID id, UUID orderId, String provider, String method, long amount, String status,
                     String providerOrderId, Instant now, UUID moneyAccountId) {
    PaymentEntity e = new PaymentEntity();
    e.id = id;
    e.orderId = orderId;
    e.provider = provider;
    e.method = method;
    e.amountPaise = amount;
    e.status = status;
    e.providerOrderId = providerOrderId;
    e.moneyAccountId = moneyAccountId;
    e.createdAt = now;
    e.updatedAt = now;
    payments.save(e);
  }

  /** The payment for a provider's order id, if any. */
  public Optional<Payment> byProviderOrder(String provider, String providerOrderId) {
    return payments.findByProviderAndProviderOrderId(provider, providerOrderId).map(PaymentRepository::toDomain);
  }

  /** The newest payment of an order, if any. */
  public Optional<Payment> latestForOrder(UUID orderId) {
    return payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).map(PaymentRepository::toDomain);
  }

  /**
   * Payments still {@code CREATED} since before {@code before}, oldest first, at most {@code limit}; the reconcile job asks the provider about them.
   */
  public List<Payment> staleCreated(Instant before, int limit) {
    return payments.staleCreated(before, PageRequest.of(0, limit)).stream().map(PaymentRepository::toDomain).toList();
  }

  /** Sets the status, stores the provider reference when one is given and replaces the failure reason. */
  @Transactional
  public void setStatus(UUID id, String status, String providerRef, String failure, Instant now) {
    payments.findById(id).ifPresent(e -> {
      e.status = status;
      if ("CAPTURED".equals(status) && e.capturedAt == null) e.capturedAt = now;
      if (providerRef != null) e.providerRef = providerRef;
      e.failureReason = failure;
      e.updatedAt = now;
    });
  }

  /** False when this provider event was already handled. */
  @Transactional
  public boolean recordEvent(String provider, String eventId, String type, String payload, Instant now) {
    if (events.existsByProviderAndProviderEventId(provider, eventId)) return false;
    PaymentEventEntity e = new PaymentEventEntity();
    e.id = UUID.randomUUID();
    e.provider = provider;
    e.providerEventId = eventId;
    e.eventType = type;
    e.payload = payload;
    e.processedAt = now;
    events.saveAndFlush(e);
    return true;
  }

  /** A refund as the admin sees it. */
  public record RefundRow(UUID id, UUID paymentId, UUID orderId, long amountPaise, String status, int attempts, String lastError,
                          String reason, Instant createdAt) {}

  /** Refunds not failed, so far, for one payment: what has been or will be paid back. */
  public long refundedSoFar(UUID paymentId) { return refunds.committedTo(paymentId); }

  /** Newest first; status null means every refund. */
  public Page<RefundRow> refundPage(String status, int page, int size) {
    PageRequest req = PageRequest.of(page, size);
    var r = status == null ? refunds.findAllByOrderByCreatedAtDesc(req) : refunds.findByStatusOrderByCreatedAtDesc(status, req);
    List<RefundRow> rows = r.getContent().stream().map(x -> new RefundRow(x.id, x.paymentId,
        payments.findById(x.paymentId).map(p -> p.orderId).orElse(null), x.amountPaise, x.status, x.attempts, x.lastError, x.reason,
        x.createdAt)).toList();
    return new Page<>(rows, page, size, r.getTotalElements());
  }

  /** How many refunds are in this state (PENDING, PROCESSING, ISSUED, FAILED). */
  public long countRefunds(String status) { return refunds.countByStatus(status); }

  /** Moves a {@code FAILED} refund back to {@code PENDING}; false when it was not failed. */
  @Transactional
  public boolean requeueFailedRefund(UUID id, Instant now) { return refunds.requeueFailed(id, now) == 1; }

  /** Refunds queued by an order cancel or a bad payment, oldest first. */
  public List<PendingRefund> pendingRefunds(int limit) {
    return refunds.pending(PageRequest.of(0, limit)).stream().flatMap(r -> payments.findById(r.paymentId).stream()
        .map(p -> new PendingRefund(r.id, p.id, p.provider, r.providerPaymentRef != null ? r.providerPaymentRef : p.providerRef, r.amountPaise, r.reason, r.attempts))).toList();
  }

  /** Takes a pending refund for sending; only one caller wins, the others get false. */
  @Transactional
  public boolean claimRefund(UUID id, Instant now) { return refunds.claim(id, now) == 1; }

  /** Returns refunds stuck in {@code PROCESSING} since before {@code before} to the queue; returns how many. */
  @Transactional
  public int requeueStuckRefunds(Instant before, Instant now) { return refunds.requeueStuck(before, now); }

  /** Marks a refund as sent, with the provider's refund reference. */
  @Transactional
  public void refundIssued(UUID id, String providerRef, Instant now) {
    refunds.findById(id).ifPresent(r -> {
      r.status = "ISSUED";
      r.providerRef = providerRef;
      r.lastError = null;
      r.updatedAt = now;
    });
  }

  /** Back to the queue for another try, or FAILED (needs a human) once the tries run out. */
  @Transactional
  public void refundFailed(UUID id, String error, int maxAttempts, Instant now) {
    refunds.findById(id).ifPresent(r -> {
      r.attempts++;
      r.status = r.attempts >= maxAttempts ? "FAILED" : "PENDING";
      r.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
      r.updatedAt = now;
    });
  }

  /** Queues a refund of part or all of the payment row's own provider payment. */
  @Transactional
  public void insertRefund(UUID paymentId, long amount, String status, String ref, String reason, Instant now) {
    insertRefund(paymentId, amount, status, ref, reason, null, now);
  }

  /** Same, for a refund of one specific provider payment (a wrong or duplicate capture) instead of the payment row's own. */
  @Transactional
  public void insertRefund(UUID paymentId, long amount, String status, String ref, String reason, String providerPaymentRef,
                           Instant now) {
    RefundEntity e = new RefundEntity();
    e.providerPaymentRef = providerPaymentRef;
    e.id = UUID.randomUUID();
    e.paymentId = paymentId;
    e.moneyAccountId = payments.findById(paymentId).map(p -> p.moneyAccountId).orElse(null); // a refund follows its payment's account
    e.amountPaise = amount;
    e.status = status;
    e.providerRef = ref;
    e.reason = reason;
    e.createdAt = now;
    e.updatedAt = now;
    refunds.save(e);
  }

  /** Every payment method with its switch, in display order. */
  public List<Setting> settings() {
    return settings.findAllByOrderBySortOrderAsc().stream().map(s -> new Setting(s.code, s.enabled)).toList();
  }

  /** Whether the method is switched on; an unknown code counts as off. */
  public boolean enabled(String code) { return settings.findById(code).map(s -> s.enabled).orElse(false); }

  /** Switches a payment method on or off. */
  @Transactional
  public void setEnabled(String code, boolean enabled) { settings.findById(code).ifPresent(s -> s.enabled = enabled); }

  /** True when this provider payment already has an automatic refund for this reason (a replayed webhook). */
  public boolean anomalyRefundExists(String providerPaymentRef, String reason) {
    return refunds.existsByProviderPaymentRefAndReason(providerPaymentRef, reason);
  }

  /** Records a wrong-amount or duplicate payment. */
  @Transactional
  public void insertAnomaly(UUID paymentId, UUID orderId, String kind, long expectedPaise, long receivedPaise, String providerRef,
                            Instant now) {
    PaymentAnomalyEntity e = new PaymentAnomalyEntity();
    e.id = UUID.randomUUID();
    e.paymentId = paymentId;
    e.orderId = orderId;
    e.kind = kind;
    e.expectedPaise = expectedPaise;
    e.receivedPaise = receivedPaise;
    e.providerRef = providerRef;
    e.createdAt = now;
    anomalies.save(e);
  }

  /** Newest first. */
  public Page<AnomalyRow> anomalyPage(int page, int size) {
    var r = anomalies.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    List<AnomalyRow> rows = r.getContent().stream().map(x -> new AnomalyRow(x.id, x.paymentId, x.orderId, x.kind, x.expectedPaise,
        x.receivedPaise, x.providerRef, x.createdAt)).toList();
    return new Page<>(rows, page, size, r.getTotalElements());
  }
}
