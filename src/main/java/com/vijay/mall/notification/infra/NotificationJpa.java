package com.vijay.mall.notification.infra;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code notification} rows: pages per user, unread count and marking read. */
interface NotificationJpa extends JpaRepository<NotificationEntity, UUID> {
  /** One page of a user's notifications read with one extra row (see {@code Window}), so no count is needed to know whether more follow. */
  List<NotificationEntity> findByUserId(UUID userId, Pageable page);

  /** Counts a user's notifications but stops at {@code limit}: exact below it, and the cost has a ceiling. */
  @Query(value = "select count(*) from (select 1 from notification n where n.user_id = :u limit :limit) c", nativeQuery = true)
  long countUpTo(@Param("u") UUID userId, @Param("limit") int limit);

  long countByUserIdAndReadAtIsNull(UUID userId);

  boolean existsByIdAndUserId(UUID id, UUID userId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update NotificationEntity n set n.readAt = :now where n.id = :id and n.userId = :u and n.readAt is null")
  int markRead(@Param("id") UUID id, @Param("u") UUID userId, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update NotificationEntity n set n.readAt = :now where n.userId = :u and n.readAt is null")
  int markAllRead(@Param("u") UUID userId, @Param("now") Instant now);
}
