package com.vijay.mall.identity.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface UserJpa extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByEmail(String email);

  Optional<UserEntity> findFirstByMobileOrderByCreatedAtAsc(String mobile);

  /** The account that proved it owns this number with an OTP. At most one exists (unique index). */
  Optional<UserEntity> findFirstByMobileAndMobileVerifiedTrue(String mobile);

  Optional<UserEntity> findFirstByMobileAndSourceOrderByCreatedAtAsc(String mobile, String source);

  List<UserEntity> findByRoleOrderByCreatedAtDesc(String role);

  boolean existsByIdAndStatus(UUID id, String status);

  @Query("select u.id from UserEntity u where u.role = 'ADMIN' and u.status = 'ACTIVE'")
  List<UUID> activeAdminIds();
}
