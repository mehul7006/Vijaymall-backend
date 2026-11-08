package com.vijay.mall.payment.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** Refund bookkeeping of PaymentRepository: retry counting, the give-up point, error truncation, claiming. */
class PaymentRepositoryCoverageTest {
  private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant T1 = Instant.parse("2026-11-01T10:05:00Z");

  private PaymentJpa payments;
  private RefundJpa refunds;
  private PaymentRepository repo;

  @BeforeEach
  void setUp() {
    payments = mock(PaymentJpa.class);
    refunds = mock(RefundJpa.class);
    repo = new PaymentRepository(payments, mock(PaymentEventJpa.class), refunds, mock(PaymentSettingJpa.class));
  }

  private RefundEntity refund(String status, int attempts) {
    RefundEntity r = new RefundEntity();
    r.id = UUID.randomUUID();
    r.paymentId = UUID.randomUUID();
    r.status = status;
    r.attempts = attempts;
    r.amountPaise = 2500;
    r.reason = "cancel";
    r.updatedAt = T0;
    when(refunds.findById(r.id)).thenReturn(Optional.of(r));
    return r;
  }

  @Test
  void aFailedAttemptGoesBackToTheQueueWhileTriesRemain() {
    RefundEntity r = refund("PROCESSING", 0);
    repo.refundFailed(r.id, "timeout", 3, T1);
    assertThat(r.attempts).isEqualTo(1);
    assertThat(r.status).isEqualTo("PENDING");
    assertThat(r.lastError).isEqualTo("timeout");
    assertThat(r.updatedAt).isEqualTo(T1);
  }

  @Test
  void theLastAllowedAttemptMarksTheRefundFailedForAHuman() {
    RefundEntity r = refund("PROCESSING", 2);
    repo.refundFailed(r.id, "still down", 3, T1);
    assertThat(r.attempts).isEqualTo(3);
    assertThat(r.status).isEqualTo("FAILED");
  }

  @Test
  void errorTextIsCutToFiveHundredCharactersAndNullStaysNull() {
    RefundEntity long1 = refund("PROCESSING", 0);
    repo.refundFailed(long1.id, "e".repeat(900), 10, T1);
    assertThat(long1.lastError).hasSize(500);
    RefundEntity exact = refund("PROCESSING", 0);
    repo.refundFailed(exact.id, "e".repeat(500), 10, T1);
    assertThat(exact.lastError).hasSize(500);
    RefundEntity none = refund("PROCESSING", 0);
    repo.refundFailed(none.id, null, 10, T1);
    assertThat(none.lastError).isNull();
    assertThat(none.status).isEqualTo("PENDING");
  }

  @Test
  void failingAnUnknownRefundDoesNothing() {
    UUID unknown = UUID.randomUUID();
    when(refunds.findById(unknown)).thenReturn(Optional.empty());
    repo.refundFailed(unknown, "x", 3, T1);
    repo.refundIssued(unknown, "rfnd", T1);
  }

  @Test
  void anIssuedRefundKeepsTheProviderReferenceAndClearsTheError() {
    RefundEntity r = refund("PROCESSING", 2);
    r.lastError = "old";
    repo.refundIssued(r.id, "rfnd_9", T1);
    assertThat(r.status).isEqualTo("ISSUED");
    assertThat(r.providerRef).isEqualTo("rfnd_9");
    assertThat(r.lastError).isNull();
    assertThat(r.updatedAt).isEqualTo(T1);
  }

  @Test
  void onlyTheServerThatWinsTheClaimSendsTheRefund() {
    UUID id = UUID.randomUUID();
    when(refunds.claim(id, T1)).thenReturn(1, 0);
    assertThat(repo.claimRefund(id, T1)).isTrue();
    assertThat(repo.claimRefund(id, T1)).isFalse();
    when(refunds.requeueStuck(T0, T1)).thenReturn(4);
    assertThat(repo.requeueStuckRefunds(T0, T1)).isEqualTo(4);
  }

  @Test
  void pendingRefundsCarryTheirPaymentAndSkipOnesWhosePaymentIsGone() {
    RefundEntity ok = refund("PENDING", 1);
    RefundEntity orphan = refund("PENDING", 0);
    PaymentEntity p = new PaymentEntity();
    p.id = ok.paymentId;
    p.provider = "razorpay";
    p.providerRef = "pay_1";
    when(refunds.pending(any(Pageable.class))).thenReturn(List.of(ok, orphan));
    when(payments.findById(ok.paymentId)).thenReturn(Optional.of(p));
    when(payments.findById(orphan.paymentId)).thenReturn(Optional.empty());
    assertThat(repo.pendingRefunds(10)).singleElement().satisfies(r -> {
      assertThat(r.id()).isEqualTo(ok.id);
      assertThat(r.provider()).isEqualTo("razorpay");
      assertThat(r.providerRef()).isEqualTo("pay_1");
      assertThat(r.amountPaise()).isEqualTo(2500);
      assertThat(r.attempts()).isEqualTo(1);
      assertThat(r.reason()).isEqualTo("cancel");
    });
  }

  @Test
  void setStatusKeepsTheOldReferenceWhenNoneIsGiven() {
    PaymentEntity p = new PaymentEntity();
    p.id = UUID.randomUUID();
    p.providerRef = "pay_old";
    when(payments.findById(p.id)).thenReturn(Optional.of(p));
    repo.setStatus(p.id, "FAILED", null, "declined", T1);
    assertThat(p.providerRef).isEqualTo("pay_old");
    assertThat(p.failureReason).isEqualTo("declined");
    repo.setStatus(p.id, "CAPTURED", "pay_new", null, T1);
    assertThat(p.providerRef).isEqualTo("pay_new");
    assertThat(p.status).isEqualTo("CAPTURED");
    assertThat(p.failureReason).isNull();
  }
}
