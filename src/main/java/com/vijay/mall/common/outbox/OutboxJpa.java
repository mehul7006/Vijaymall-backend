package com.vijay.mall.common.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

interface OutboxJpa extends JpaRepository<OutboxEventEntity, UUID> {
  long countByPublishedAtIsNull();

  /** Locks the rows it returns and skips rows another instance already holds (SKIP LOCKED = lock timeout -2). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query("select e from OutboxEventEntity e where e.publishedAt is null order by e.createdAt")
  List<OutboxEventEntity> nextBatch(Pageable page);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from OutboxEventEntity e where e.publishedAt is not null and e.publishedAt < :before")
  int purge(@Param("before") Instant before);
}
