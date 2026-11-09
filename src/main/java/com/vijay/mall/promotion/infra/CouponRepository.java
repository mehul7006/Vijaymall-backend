package com.vijay.mall.promotion.infra;

import com.vijay.mall.promotion.domain.AppliesTo;
import com.vijay.mall.promotion.domain.Coupon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores coupons, their category, product and product group limits and their redemptions, and exposes coupons as {@link Coupon} records. */
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

  /** The category, product and product group ids one coupon is limited to. */
  private record Targets(List<UUID> categories, List<UUID> products, List<UUID> groups) {}

  private static List<UUID> sorted(List<UUID> ids) {
    List<UUID> out = new ArrayList<>(ids);
    // the database's own order for ids (UUID.compareTo compares signed numbers and would disagree with it)
    out.sort(Comparator.comparing(UUID::toString));
    return out;
  }

  private static Coupon toDomain(CouponEntity e, Targets targets) {
    List<UUID> ids = sorted(targets.categories());
    if (e.categoryId != null && ids.remove(e.categoryId)) ids.add(0, e.categoryId); // keep the first category first
    return new Coupon(e.id, e.code, e.kind, e.amount, e.minOrderPaise, e.maxDiscountPaise, e.categoryId, e.perUserLimit,
        e.totalLimit, e.usedCount, e.startAt, e.endAt, e.active, e.worksWithCoins, e.firstOrderOnly, ids, sorted(targets.products()), sorted(targets.groups()));
  }

  /**
   * One query for all three limit tables of these coupons (rows of coupon id, kind C/P/G and the id), so reading coupons never
   * costs one query per coupon or per table.
   */
  @SuppressWarnings("unchecked")
  private Map<UUID, Targets> targetsOf(Collection<UUID> couponIds) {
    List<Object[]> rows = em.createNativeQuery("""
        select coupon_id, 'C', category_id from coupon_category where coupon_id in (:ids)
        union all select coupon_id, 'P', product_id from coupon_product where coupon_id in (:ids)
        union all select coupon_id, 'G', product_group_id from coupon_product_group where coupon_id in (:ids)
        """).setParameter("ids", couponIds).getResultList();
    Map<UUID, Targets> out = new HashMap<>();
    for (Object[] r : rows) {
      Targets t = out.computeIfAbsent((UUID) r[0], k -> new Targets(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
      String kind = (String) r[1];
      (kind.equals("C") ? t.categories() : kind.equals("P") ? t.products() : t.groups()).add((UUID) r[2]);
    }
    return out;
  }

  private List<Coupon> toDomains(List<CouponEntity> list) {
    if (list.isEmpty()) return List.of();
    Map<UUID, Targets> byCoupon = targetsOf(list.stream().map(e -> e.id).toList());
    Targets none = new Targets(List.of(), List.of(), List.of());
    return list.stream().map(e -> toDomain(e, byCoupon.getOrDefault(e.id, none))).toList();
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

  private void replaceItems(UUID couponId, Collection<UUID> productIds, Collection<UUID> groupIds) {
    em.flush();
    em.createNativeQuery("delete from coupon_product where coupon_id = :c").setParameter("c", couponId).executeUpdate();
    em.createNativeQuery("delete from coupon_product_group where coupon_id = :c").setParameter("c", couponId).executeUpdate();
    for (UUID p : productIds) {
      em.createNativeQuery("insert into coupon_product (coupon_id, product_id) values (:c, :i)")
          .setParameter("c", couponId).setParameter("i", p).executeUpdate();
    }
    for (UUID g : groupIds) {
      em.createNativeQuery("insert into coupon_product_group (coupon_id, product_group_id) values (:c, :i)")
          .setParameter("c", couponId).setParameter("i", g).executeUpdate();
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

  /** Saves a new coupon with its category, product and product group limits and returns its id; the used count starts at 0. */
  @Transactional
  public UUID insert(Coupon c) {
    CouponEntity e = new CouponEntity();
    e.id = UUID.randomUUID();
    e.usedCount = 0;
    fill(e, c);
    UUID id = coupons.save(e).id;
    replaceCategories(id, c.categoryIds());
    replaceItems(id, c.productIds(), c.productGroupIds());
    return id;
  }

  /** Overwrites a coupon's settings and replaces its category, product and product group limits; the used count is kept. */
  @Transactional
  public void update(UUID id, Coupon c) {
    coupons.findById(id).ifPresent(e -> {
      fill(e, c);
      replaceCategories(id, c.categoryIds());
      replaceItems(id, c.productIds(), c.productGroupIds());
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

  /** Which of these category ids exist. */
  public Set<UUID> existingCategories(Collection<UUID> ids) { return existing("select id from category where id in (:ids)", ids); }

  /** Which of these product ids exist (switched on or off). */
  public Set<UUID> existingProducts(Collection<UUID> ids) { return existing("select id from product where id in (:ids)", ids); }

  /** Which of these product group ids exist, that is, are the group of at least one product. */
  public Set<UUID> existingGroups(Collection<UUID> ids) {
    return existing("select distinct product_group_id from product where product_group_id in (:ids)", ids);
  }

  @SuppressWarnings("unchecked")
  private Set<UUID> existing(String sql, Collection<UUID> ids) {
    if (ids.isEmpty()) return Set.of();
    return new HashSet<>((List<UUID>) em.createNativeQuery(sql).setParameter("ids", ids).getResultList());
  }

  /**
   * Names and on/off state of every category, product and product group any of these coupons lists, in three queries for all
   * coupons together. A product shows as "Name Pack" (for example "Basmati Rice 5 kg"); a product group as the name of its product.
   */
  public Names namesFor(Collection<Coupon> list) {
    Set<UUID> cats = new HashSet<>();
    Set<UUID> prods = new HashSet<>();
    Set<UUID> groups = new HashSet<>();
    list.forEach(c -> {
      cats.addAll(c.categoryIds());
      prods.addAll(c.productIds());
      groups.addAll(c.productGroupIds());
    });
    return new Names(
        lookup("select id, name_en, active from category where id in (:ids)", cats),
        lookup("select id, trim(name_en || ' ' || pack_size), active from product where id in (:ids)", prods),
        lookup("select product_group_id, min(name_en), bool_or(active) from product where product_group_id in (:ids) "
            + "group by product_group_id", groups));
  }

  @SuppressWarnings("unchecked")
  private Map<UUID, AppliesTo.Named> lookup(String sql, Set<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<UUID, AppliesTo.Named> out = new HashMap<>();
    for (Object[] r : (List<Object[]>) em.createNativeQuery(sql).setParameter("ids", ids).getResultList()) {
      out.put((UUID) r[0], new AppliesTo.Named((UUID) r[0], (String) r[1], (Boolean) r[2]));
    }
    return out;
  }

  /** Names by id for categories, products and product groups; an id missing here no longer exists. */
  public record Names(Map<UUID, AppliesTo.Named> categories, Map<UUID, AppliesTo.Named> products, Map<UUID, AppliesTo.Named> groups) {
    /** What the coupon is for, with names in the coupon's own order. */
    public AppliesTo of(Coupon c) {
      return AppliesTo.of(pick(categories, c.categoryIds()), pick(products, c.productIds()), pick(groups, c.productGroupIds()));
    }

    private static List<AppliesTo.Named> pick(Map<UUID, AppliesTo.Named> from, List<UUID> ids) {
      return ids.stream().map(from::get).filter(Objects::nonNull).toList();
    }
  }
}
