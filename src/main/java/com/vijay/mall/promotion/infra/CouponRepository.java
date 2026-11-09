package com.vijay.mall.promotion.infra;

import com.vijay.mall.promotion.domain.Coupon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores coupons, their category limits and their redemptions, and exposes coupons as {@link Coupon} records. */
@Repository
public class CouponRepository {
  private final CouponJpa coupons;
  private final RedemptionJpa redemptions;
  private final CouponCategoryJpa categories;

  @PersistenceContext
  private EntityManager em;

  CouponRepository(CouponJpa coupons, RedemptionJpa redemptions, CouponCategoryJpa categories) {
    this.coupons = coupons;
    this.redemptions = redemptions;
    this.categories = categories;
  }

  private static Coupon toDomain(CouponEntity e, Map<UUID, List<UUID>> categoriesByCoupon) {
    List<UUID> ids = new ArrayList<>(categoriesByCoupon.getOrDefault(e.id, List.of()));
    // the database's own order for ids (UUID.compareTo compares signed numbers and would disagree with it)
    ids.sort(Comparator.comparing(UUID::toString));
    if (e.categoryId != null && ids.remove(e.categoryId)) ids.add(0, e.categoryId); // keep the first category first
    return new Coupon(e.id, e.code, e.kind, e.amount, e.minOrderPaise, e.maxDiscountPaise, e.categoryId, e.perUserLimit,
        e.totalLimit, e.usedCount, e.startAt, e.endAt, e.active, e.worksWithCoins, e.firstOrderOnly, ids);
  }

  private List<Coupon> toDomains(List<CouponEntity> list) {
    if (list.isEmpty()) return List.of();
    Map<UUID, List<UUID>> byCoupon = new HashMap<>();
    categories.findByCouponIdInOrderByCategoryId(list.stream().map(e -> e.id).toList())
        .forEach(r -> byCoupon.computeIfAbsent(r.couponId, k -> new ArrayList<>()).add(r.categoryId));
    return list.stream().map(e -> toDomain(e, byCoupon)).toList();
  }

  private Optional<Coupon> toDomain(Optional<CouponEntity> e) {
    return e.map(x -> toDomains(List.of(x)).get(0));
  }

  private void replaceCategories(UUID couponId, Collection<UUID> ids) {
    categories.deleteAllFor(couponId);
    for (UUID categoryId : ids) {
      CouponCategoryEntity row = new CouponCategoryEntity();
      row.couponId = couponId;
      row.categoryId = categoryId;
      categories.save(row);
    }
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

  /** Every coupon, by code. */
  public List<Coupon> all() { return toDomains(coupons.findAllByOrderByCodeAsc()); }

  /** Every coupon that is switched on, by code. */
  public List<Coupon> allActive() { return toDomains(coupons.findAllByActiveTrueOrderByCodeAsc()); }

  /** The coupon with this id, if any. */
  public Optional<Coupon> find(UUID id) { return toDomain(coupons.findById(id)); }

  /** The coupon with this (already normalised, upper-case) code, if any. */
  public Optional<Coupon> findByCode(String code) { return toDomain(coupons.findByCode(code)); }

  /** Saves a new coupon with its category limits and returns its id; the used count starts at 0. */
  @Transactional
  public UUID insert(Coupon c) {
    CouponEntity e = new CouponEntity();
    e.id = UUID.randomUUID();
    e.usedCount = 0;
    fill(e, c);
    UUID id = coupons.save(e).id;
    replaceCategories(id, c.categoryIds());
    return id;
  }

  /** Overwrites a coupon's settings and replaces its category limits; the used count is kept. */
  @Transactional
  public void update(UUID id, Coupon c) {
    coupons.findById(id).ifPresent(e -> {
      fill(e, c);
      replaceCategories(id, c.categoryIds());
    });
  }

  /** How many times the customer has used the coupon, not counting uses given back. */
  public int userUses(UUID couponId, UUID userId) {
    return (int) redemptions.countByCouponIdAndUserIdAndReleasedFalse(couponId, userId);
  }

  /**
   * Makes orders of the same customer and coupon wait for each other until their transaction ends, so the per-customer
   * limit cannot be beaten by two orders placed at once. Other customers and other coupons are not held up.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockCustomerUse(UUID couponId, UUID userId) {
    em.createNativeQuery("select count(*) from (select pg_advisory_xact_lock(hashtextextended(:k, 0))) as locked")
        .setParameter("k", "coupon-use:" + couponId + ":" + userId).getSingleResult();
  }

  /** Atomic: counts a use only while the total limit allows it. */
  @Transactional
  public int takeUse(UUID couponId) { return coupons.takeUse(couponId); }

  /** Records that an order used the coupon and the discount (in paise) it received. */
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

  /** The coupon an order currently holds, or empty if it has none or it was already given back. */
  public Optional<UUID> openRedemptionCoupon(UUID orderId) {
    return redemptions.findByOrderIdAndReleasedFalse(orderId).map(r -> r.couponId);
  }

  /** Marks the order's redemption as given back and returns one use to the coupon's counter. */
  @Transactional
  public void releaseRedemption(UUID orderId, UUID couponId) {
    redemptions.findByOrderIdAndReleasedFalse(orderId).ifPresent(r -> r.released = true);
    redemptions.flush();
    coupons.giveBackUse(couponId);
  }

  /** How many redemptions the coupon has ever had, released ones included. */
  public long redemptionCount(UUID couponId) { return redemptions.countByCouponId(couponId); }

  /** Deletes a coupon that was never used. */
  @Transactional
  public void delete(UUID id) { coupons.deleteById(id); }

  /** Switches a coupon off without deleting it (used when it has history). */
  @Transactional
  public void deactivate(UUID id) { coupons.deactivate(id); }
}
