package com.vijay.mall.promotion.infra;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface RedemptionJpa extends JpaRepository<CouponRedemptionEntity, UUID> {
  long countByCouponIdAndUserIdAndReleasedFalse(UUID couponId, UUID userId);

  long countByCouponId(UUID couponId);

  Optional<CouponRedemptionEntity> findByOrderIdAndReleasedFalse(UUID orderId);
}
