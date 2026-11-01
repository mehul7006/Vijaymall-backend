package com.vijay.mall.identity.infra;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OtpJpa extends JpaRepository<OtpChallengeEntity, UUID> {
  Optional<OtpChallengeEntity> findFirstByEmailAndConsumedFalseOrderByCreatedAtDesc(String email);

  Optional<OtpChallengeEntity> findFirstByMobileAndConsumedFalseOrderByCreatedAtDesc(String mobile);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update OtpChallengeEntity o set o.attempts = o.attempts + 1 where o.id = :id")
  int addAttempt(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update OtpChallengeEntity o set o.consumed = true where o.id = :id")
  int consume(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from OtpChallengeEntity o where o.createdAt < :cutoff")
  int deleteOlderThan(@Param("cutoff") Instant cutoff);
}
