package com.vijay.mall.payment.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RefundJpa extends JpaRepository<RefundEntity, UUID> {
  @Query("select coalesce(sum(r.amountPaise), 0) from RefundEntity r where r.paymentId = :payment and r.status <> 'FAILED'")
  long committedTo(@Param("payment") UUID paymentId);

  org.springframework.data.domain.Page<RefundEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable page);

  org.springframework.data.domain.Page<RefundEntity> findAllByOrderByCreatedAtDesc(Pageable page);

  long countByStatus(String status);

  /** Puts a FAILED refund back in the queue for a fresh run of tries. */
  @Modifying
  @Query("update RefundEntity r set r.status = 'PENDING', r.attempts = 0, r.updatedAt = :now where r.id = :id and r.status = 'FAILED'")
  int requeueFailed(@Param("id") UUID id, @Param("now") Instant now);

  @Query("select r from RefundEntity r where r.status = 'PENDING' order by r.createdAt")
  List<RefundEntity> pending(Pageable page);

  /** A refund stuck in PROCESSING (the server stopped mid-call) goes back to the queue after a while. */
  @Modifying
  @Query("update RefundEntity r set r.status = 'PENDING', r.updatedAt = :now where r.status = 'PROCESSING' and r.updatedAt < :before")
  int requeueStuck(@Param("before") Instant before, @Param("now") Instant now);

  /** Takes a refund for sending. Only one caller can win, so two servers never refund the same payment twice. */
  @Modifying
  @Query("update RefundEntity r set r.status = 'PROCESSING', r.updatedAt = :now where r.id = :id and r.status = 'PENDING'")
  int claim(@Param("id") UUID id, @Param("now") Instant now);
}
