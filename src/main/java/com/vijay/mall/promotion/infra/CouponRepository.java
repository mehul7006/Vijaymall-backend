package com.vijay.mall.promotion.infra;

import com.vijay.mall.promotion.domain.Coupon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CouponRepository {
  private final CouponJpa coupons;
  private final RedemptionJpa redemptions;

  @PersistenceContext
  private EntityManager em;

  CouponRepository(CouponJpa coupons, RedemptionJpa redemptions) {
    this.coupons = coupons;
    this.redemptions = redemptions;
  }

  private static Coupon toDomain(CouponEntity e) {
    return new Coupon(e.id, e.code, e.kind, e.amount, e.minOrderPaise, e.maxDiscountPaise, e.categoryId, e.perUserLimit,
        e.totalLimit, e.usedCount, e.startAt, e.endAt, e.active, e.worksWithCoins, e.firstOrderOnly);
  }

  private static void fill(CouponEntity e, Coupon c) {
    e.code = c.code();
    e.kind = c.kind();
    e.amount = c.value();
    e.minOrderPaise = c.minOrderPaise();
    e.maxDiscountPaise = c.maxDiscountPaise();
    e.categoryId = c.categoryId();
    e.perUserLimit = c.perUserLimit();
    e.totalLimit = c.totalLimit();
    e.startAt = c.startAt();
    e.endAt = c.endAt();
    e.active = c.active();
    e.worksWithCoins = c.worksWithCoins();
    e.firstOrderOnly = c.firstOrderOnly();
  }

  public List<Coupon> all() { return coupons.findAllByOrderByCodeAsc().stream().map(CouponRepository::toDomain).toList(); }

  public List<Coupon> allActive() {
    return coupons.findAllByActiveTrueOrderByCodeAsc().stream().map(CouponRepository::toDomain).toList();
  }

  public Optional<Coupon> find(UUID id) { return coupons.findById(id).map(CouponRepository::toDomain); }

  public Optional<Coupon> findByCode(String code) { return coupons.findByCode(code).map(CouponRepository::toDomain); }

  @Transactional
  public UUID insert(Coupon c) {
    CouponEntity e = new CouponEntity();
    e.id = UUID.randomUUID();
    e.usedCount = 0;
    fill(e, c);
    return coupons.save(e).id;
  }

  @Transactional
  public void update(UUID id, Coupon c) { coupons.findById(id).ifPresent(e -> fill(e, c)); }

  public int userUses(UUID couponId, UUID userId) {
    return (int) redemptions.countByCouponIdAndUserIdAndReleasedFalse(couponId, userId);
  }

  /**
   * Makes orders of the same customer and coupon wait for each other until their transaction ends, so the per-customer
   * limit cannot be beaten by two orders placed at once. Other customers and other coupons are not held up.
   */
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
  public void lockCustomerUse(UUID couponId, UUID userId) {
    em.createNativeQuery("select count(*) from (select pg_advisory_xact_lock(hashtextextended(:k, 0))) as locked")
        .setParameter("k", "coupon-use:" + couponId + ":" + userId).getSingleResult();
  }

  /** Atomic: counts a use only while the total limit allows it. */
  @Transactional
  public int takeUse(UUID couponId) { return coupons.takeUse(couponId); }

  @Transactional
  public void insertRedemption(UUID couponId, UUID userId, UUID orderId, long discount) {
    CouponRedemptionEntity r = new CouponRedemptionEntity();
    r.id = UUID.randomUUID();
    r.couponId = couponId;
    r.userId = userId;
    r.orderId = orderId;
    r.discountPaise = discount;
    redemptions.save(r);
  }

  public Optional<UUID> openRedemptionCoupon(UUID orderId) {
    return redemptions.findByOrderIdAndReleasedFalse(orderId).map(r -> r.couponId);
  }

  @Transactional
  public void releaseRedemption(UUID orderId, UUID couponId) {
    redemptions.findByOrderIdAndReleasedFalse(orderId).ifPresent(r -> r.released = true);
    redemptions.flush();
    coupons.giveBackUse(couponId);
  }

  public long redemptionCount(UUID couponId) { return redemptions.countByCouponId(couponId); }

  @Transactional
  public void delete(UUID id) { coupons.deleteById(id); }

  @Transactional
  public void deactivate(UUID id) { coupons.deactivate(id); }
}
