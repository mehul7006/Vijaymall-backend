package com.vijay.mall.promotion.infra;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data access to {@code coupon_category}: the categories a coupon is limited to. */
interface CouponCategoryJpa extends JpaRepository<CouponCategoryEntity, CouponCategoryId> {
  List<CouponCategoryEntity> findByCouponIdInOrderByCategoryId(Collection<UUID> couponIds);

  /** Runs at once (not at flush), so the new set can be written under the same keys in the same transaction. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("delete from CouponCategoryEntity c where c.couponId = :id")
  int deleteAllFor(@Param("id") UUID couponId);
}
