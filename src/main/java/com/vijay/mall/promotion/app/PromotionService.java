package com.vijay.mall.promotion.app;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.CouponContext;
import com.vijay.mall.promotion.domain.CouponMath;
import com.vijay.mall.promotion.domain.CouponRules;
import com.vijay.mall.promotion.infra.CouponRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public interface of the promotion module. */
@Service
public class PromotionService {
  private final CouponRepository coupons;
  private final AuditService audit;
  private final Clock clock;

  public PromotionService(CouponRepository coupons, AuditService audit, Clock clock) {
    this.coupons = coupons;
    this.audit = audit;
    this.clock = clock;
  }

  public static String normalize(String code) { return code == null ? null : code.trim().toUpperCase(); }

  private static ApiException invalid(String reason) {
    return ApiException.unprocessable("COUPON_INVALID", "This coupon cannot be used", Map.of("reason", reason));
  }

  /** What a coupon does to a cart: the discount, and whether coins may still be spent in the same order. */
  public record Applied(long discountPaise, boolean worksWithCoins) {}

  /** Discount in paise for a code on a priced cart; throws COUPON_INVALID with a reason when not usable. */
  public long discountFor(String code, UUID userId, List<PricedLine> lines, long subtotalPaise) {
    return apply(code, userId, lines, subtotalPaise, () -> 0).discountPaise();
  }

  /**
   * Checks every coupon rule and prices the discount. {@code priorOrders} counts the customer's earlier orders; it is
   * only called when the coupon is for the first order, so other coupons cost no extra query.
   */
  public Applied apply(String code, UUID userId, List<PricedLine> lines, long subtotalPaise,
                       java.util.function.IntSupplier priorOrders) {
    Coupon c = coupons.findByCode(normalize(code)).orElseThrow(() -> invalid("NOT_FOUND"));
    int uses = coupons.userUses(c.id(), userId);
    int prior = c.firstOrderOnly() ? priorOrders.getAsInt() : 0;
    var ctx = new CouponContext(c, clock.instant(), subtotalPaise, lines, uses, prior);
    CouponRules.firstViolation(CouponRules.STANDARD, ctx).ifPresent(r -> { throw invalid(r); });
    return new Applied(CouponMath.discount(c, CouponMath.base(c, lines, subtotalPaise)), c.worksWithCoins());
  }

  /** Counts one use for an order. Called inside the order transaction. */
  @Transactional
  public void consume(String code, UUID userId, UUID orderId, long discount) {
    if (code == null || code.isBlank()) return;
    Coupon c = coupons.findByCode(normalize(code)).orElseThrow(() -> invalid("NOT_FOUND"));
    if (coupons.takeUse(c.id()) == 0) throw invalid("LIMIT_REACHED");
    coupons.insertRedemption(c.id(), userId, orderId, discount);
  }

  /** Gives the use back when an order is cancelled or expires. */
  @Transactional
  public void release(UUID orderId) {
    coupons.openRedemptionCoupon(orderId).ifPresent(cid -> coupons.releaseRedemption(orderId, cid));
  }

  // ---------- admin ----------
  public List<Coupon> list() { return coupons.all(); }

  @Transactional
  public Coupon create(Coupon in, UUID actor) {
    check(in);
    if (coupons.findByCode(normalize(in.code())).isPresent()) throw ApiException.validation("code", "code already exists");
    UUID id = coupons.insert(normalized(in));
    audit.log(actor, "CREATE", "coupon", id, in.code());
    return coupons.find(id).orElseThrow();
  }

  @Transactional
  public Coupon update(UUID id, Coupon in, UUID actor) {
    check(in);
    coupons.find(id).orElseThrow(() -> ApiException.notFound("Coupon"));
    coupons.findByCode(normalize(in.code())).filter(x -> !x.id().equals(id))
        .ifPresent(x -> { throw ApiException.validation("code", "code already exists"); });
    coupons.update(id, normalized(in));
    audit.log(actor, "UPDATE", "coupon", id, in.code());
    return coupons.find(id).orElseThrow();
  }

  @Transactional
  public void delete(UUID id, UUID actor) {
    coupons.find(id).orElseThrow(() -> ApiException.notFound("Coupon"));
    if (coupons.redemptionCount(id) > 0) coupons.deactivate(id); else coupons.delete(id);
    audit.log(actor, "DELETE", "coupon", id, null);
  }

  private static Coupon normalized(Coupon c) {
    return new Coupon(c.id(), normalize(c.code()), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(),
        c.categoryId(), c.perUserLimit(), c.totalLimit(), c.usedCount(), c.startAt(), c.endAt(), c.active(),
        c.worksWithCoins(), c.firstOrderOnly());
  }

  private static void check(Coupon c) {
    if (c.code() == null || c.code().isBlank()) throw ApiException.validation("code", "code is required");
    if (!Coupon.FLAT.equals(c.kind()) && !Coupon.PERCENT.equals(c.kind())) throw ApiException.validation("kind", "FLAT or PERCENT");
    if (c.value() <= 0) throw ApiException.validation("value", "must be positive");
    if (Coupon.PERCENT.equals(c.kind()) && c.value() > 100) throw ApiException.validation("value", "percent cannot exceed 100");
    if (c.startAt() != null && c.endAt() != null && c.endAt().isBefore(c.startAt())) throw ApiException.validation("endAt", "must be after startAt");
  }
}
