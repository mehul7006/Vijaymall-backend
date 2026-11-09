package com.vijay.mall.promotion.infra;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code coupon} rows, including the atomic use counting. */
interface CouponJpa extends JpaRepository<CouponEntity, UUID> {
  List<CouponEntity> findAllByOrderByCodeAsc();

  List<CouponEntity> findAllByActiveTrueOrderByCodeAsc();

  Optional<CouponEntity> findByCode(String code);

  /** Atomic: counts a use only while the total limit allows it. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update CouponEntity c set c.usedCount = c.usedCount + 1 where c.id = :id and (c.totalLimit = 0 or c.usedCount < c.totalLimit)")
  int takeUse(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update CouponEntity c set c.usedCount = case when c.usedCount > 0 then c.usedCount - 1 else 0 end where c.id = :id")
  int giveBackUse(@Param("id") UUID id);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update CouponEntity c set c.active = false where c.id = :id")
  int deactivate(@Param("id") UUID id);
}
