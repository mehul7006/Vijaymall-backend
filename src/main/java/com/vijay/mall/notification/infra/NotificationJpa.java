package com.vijay.mall.notification.infra;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code notification} rows: pages per user, unread count and marking read. */
interface NotificationJpa extends JpaRepository<NotificationEntity, UUID> {
  Page<NotificationEntity> findByUserId(UUID userId, Pageable page);

  long countByUserIdAndReadAtIsNull(UUID userId);

  boolean existsByIdAndUserId(UUID id, UUID userId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update NotificationEntity n set n.readAt = :now where n.id = :id and n.userId = :u and n.readAt is null")
  int markRead(@Param("id") UUID id, @Param("u") UUID userId, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update NotificationEntity n set n.readAt = :now where n.userId = :u and n.readAt is null")
  int markAllRead(@Param("u") UUID userId, @Param("now") Instant now);
}
