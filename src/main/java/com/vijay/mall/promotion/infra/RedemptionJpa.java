package com.vijay.mall.promotion.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to {@code coupon_redemption}: how often a customer used a coupon and which coupon an order holds.
 */
interface RedemptionJpa extends JpaRepository<CouponRedemptionEntity, UUID> {
  long countByCouponIdAndUserIdAndReleasedFalse(UUID couponId, UUID userId);

  long countByCouponId(UUID couponId);

  Optional<CouponRedemptionEntity> findByOrderIdAndReleasedFalse(UUID orderId);
}
