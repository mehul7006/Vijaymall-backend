package com.vijay.mall.payment.infra;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentRepository {
  public record Payment(UUID id, UUID orderId, String provider, long amountPaise, String status,
                        String providerOrderId, String providerRef) {}

  public record Setting(String code, boolean enabled) {}

  private final PaymentJpa payments;
  private final PaymentEventJpa events;
  private final RefundJpa refunds;
  private final PaymentSettingJpa settings;

  PaymentRepository(PaymentJpa payments, PaymentEventJpa events, RefundJpa refunds, PaymentSettingJpa settings) {
    this.payments = payments;
    this.events = events;
    this.refunds = refunds;
    this.settings = settings;
  }

  private static Payment toDomain(PaymentEntity e) {
    return new Payment(e.id, e.orderId, e.provider, e.amountPaise, e.status, e.providerOrderId, e.providerRef);
  }

  @Transactional
  public void insert(UUID id, UUID orderId, String provider, String method, long amount, String status,
                     String providerOrderId, Instant now) {
    PaymentEntity e = new PaymentEntity();
    e.id = id;
    e.orderId = orderId;
    e.provider = provider;
    e.method = method;
    e.amountPaise = amount;
    e.status = status;
    e.providerOrderId = providerOrderId;
    e.createdAt = now;
    e.updatedAt = now;
    payments.save(e);
  }

  public Optional<Payment> byProviderOrder(String provider, String providerOrderId) {
    return payments.findByProviderAndProviderOrderId(provider, providerOrderId).map(PaymentRepository::toDomain);
  }

  public Optional<Payment> latestForOrder(UUID orderId) {
    return payments.findFirstByOrderIdOrderByCreatedAtDesc(orderId).map(PaymentRepository::toDomain);
  }

  public List<Payment> staleCreated(Instant before, int limit) {
    return payments.staleCreated(before, PageRequest.of(0, limit)).stream().map(PaymentRepository::toDomain).toList();
  }

  @Transactional
  public void setStatus(UUID id, String status, String providerRef, String failure, Instant now) {
    payments.findById(id).ifPresent(e -> {
      e.status = status;
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

  @Transactional
  public void insertRefund(UUID paymentId, long amount, String status, String ref, String reason, Instant now) {
    RefundEntity e = new RefundEntity();
    e.id = UUID.randomUUID();
    e.paymentId = paymentId;
    e.amountPaise = amount;
    e.status = status;
    e.providerRef = ref;
    e.reason = reason;
    e.createdAt = now;
    refunds.save(e);
  }

  public List<Setting> settings() {
    return settings.findAllByOrderBySortOrderAsc().stream().map(s -> new Setting(s.code, s.enabled)).toList();
  }

  public boolean enabled(String code) { return settings.findById(code).map(s -> s.enabled).orElse(false); }

  @Transactional
  public void setEnabled(String code, boolean enabled) { settings.findById(code).ifPresent(s -> s.enabled = enabled); }
}
