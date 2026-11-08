package com.vijay.mall.identity.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpa extends JpaRepository<UserEntity, UUID> {
  Optional<UserEntity> findByEmail(String email);

  Optional<UserEntity> findFirstByMobileOrderByCreatedAtAsc(String mobile);

  /** The account that proved it owns this number with an OTP. At most one exists (unique index). */
  Optional<UserEntity> findFirstByMobileAndMobileVerifiedTrue(String mobile);

  Optional<UserEntity> findFirstByMobileAndSourceOrderByCreatedAtAsc(String mobile, String source);

  List<UserEntity> findByRoleOrderByCreatedAtDesc(String role);

  boolean existsByIdAndStatus(UUID id, String status);

  @Query("select u from UserEntity u where u.role = 'ADMIN' and u.status = 'ACTIVE' and u.approvalPinHash is not null")
  List<UserEntity> adminsWithPin();

  @Query("select u from UserEntity u where u.role = 'CUSTOMER' and coalesce(u.email, '') not like 'deleted-%@deleted.invalid' "
      + "and (:q = '' or lower(coalesce(u.name, '')) like :pat or lower(coalesce(u.email, '')) like :pat or coalesce(u.mobile, '') like :pat) "
      + "order by u.createdAt desc")
  org.springframework.data.domain.Page<UserEntity> searchCustomers(@Param("q") String q, @Param("pat") String pattern,
                                                                    org.springframework.data.domain.Pageable page);

  @Query("select u.id from UserEntity u where u.role = 'ADMIN' and u.status = 'ACTIVE'")
  List<UUID> activeAdminIds();

  @Query("select u.id from UserEntity u where u.role in ('ADMIN', 'CASHIER') and u.status = 'ACTIVE'")
  List<UUID> activeStaffIds();
}
