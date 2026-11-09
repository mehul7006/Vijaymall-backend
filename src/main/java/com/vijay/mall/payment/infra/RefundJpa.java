package com.vijay.mall.payment.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefundJpa extends JpaRepository<RefundEntity, UUID> {
  /** What has been or will be paid back for a payment; automatic refunds of other (wrong or duplicate) provider payments do not count. */
  @Query("select coalesce(sum(r.amountPaise), 0) from RefundEntity r where r.paymentId = :payment and r.status <> 'FAILED' and r.providerPaymentRef is null")
  long committedTo(@Param("payment") UUID paymentId);

  /** True when this provider payment already has an automatic refund for this reason. */
  boolean existsByProviderPaymentRefAndReason(String providerPaymentRef, String reason);

  Page<RefundEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

  Page<RefundEntity> findAllByOrderByCreatedAtDesc(Pageable page);

  long countByStatus(String status);

  /** Puts a FAILED refund back in the queue for a fresh run of tries. */
  @Modifying
  @Query("update RefundEntity r set r.status = 'PENDING', r.attempts = 0, r.updatedAt = :now where r.id = :id and r.status = 'FAILED'")
  int requeueFailed(@Param("id") UUID id, @Param("now") Instant now);

  @Query("select r from RefundEntity r where r.status = 'PENDING' order by r.createdAt")
  List<RefundEntity> pending(Pageable page);

  /**
   * A refund stuck in PROCESSING (the server stopped mid-call) goes back to the queue after a while. It counts as a try:
   * the provider may have accepted the refund before the crash, so the next run looks for it before sending again.
   */
  @Modifying
  @Query("update RefundEntity r set r.status = 'PENDING', r.attempts = r.attempts + 1, r.updatedAt = :now where r.status = 'PROCESSING' and r.updatedAt < :before")
  int requeueStuck(@Param("before") Instant before, @Param("now") Instant now);

  /** Takes a refund for sending. Only one caller can win, so two servers never refund the same payment twice. */
  @Modifying
  @Query("update RefundEntity r set r.status = 'PROCESSING', r.updatedAt = :now where r.id = :id and r.status = 'PENDING'")
  int claim(@Param("id") UUID id, @Param("now") Instant now);
}
