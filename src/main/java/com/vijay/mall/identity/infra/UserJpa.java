package com.vijay.mall.identity.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to {@code users} rows: lookups by e-mail or mobile, role/status filters and the admin customer search.
 */
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
  Page<UserEntity> searchCustomers(@Param("q") String q, @Param("pat") String pattern,
                                                                    Pageable page);

  /** The same customers as {@link #searchCustomers} as one list page without a count (the page asks for one extra row). */
  @Query("select u from UserEntity u where u.role = 'CUSTOMER' and coalesce(u.email, '') not like 'deleted-%@deleted.invalid' "
      + "and (:q = '' or lower(coalesce(u.name, '')) like :pat or lower(coalesce(u.email, '')) like :pat or coalesce(u.mobile, '') like :pat) "
      + "order by u.createdAt desc")
  List<UserEntity> listCustomers(@Param("q") String q, @Param("pat") String pattern, Pageable page);

  /** Counts those customers but stops at {@code limit}: exact below it, and the cost has a ceiling however many customers there are. */
  @Query(value = "select count(*) from (select 1 from users u where u.role = 'CUSTOMER' and coalesce(u.email, '') not like 'deleted-%@deleted.invalid' "
      + "and (:q = '' or lower(coalesce(u.name, '')) like :pat or lower(coalesce(u.email, '')) like :pat or coalesce(u.mobile, '') like :pat) "
      + "limit :limit) c", nativeQuery = true)
  long countCustomersUpTo(@Param("q") String q, @Param("pat") String pattern, @Param("limit") int limit);

  /** Ids of users whose name, e-mail or mobile contains the (lower-case, %-wrapped) pattern; newest accounts first. */
  @Query("select u.id from UserEntity u where lower(coalesce(u.name, '')) like :pat or lower(coalesce(u.email, '')) like :pat "
      + "or coalesce(u.mobile, '') like :pat order by u.createdAt desc")
  List<UUID> idsMatching(@Param("pat") String pattern, Pageable page);

  @Query("select u.id from UserEntity u where u.role = 'ADMIN' and u.status = 'ACTIVE'")
  List<UUID> activeAdminIds();

  @Query("select u.id from UserEntity u where u.role in ('ADMIN', 'CASHIER') and u.status = 'ACTIVE'")
  List<UUID> activeStaffIds();
}
