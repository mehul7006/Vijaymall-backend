package com.vijay.mall.promotion.app;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.Limits;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.CouponContext;
import com.vijay.mall.promotion.domain.CouponMath;
import com.vijay.mall.promotion.domain.CouponRules;
import com.vijay.mall.promotion.domain.PublicCoupon;
import com.vijay.mall.promotion.infra.CouponRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public interface of the promotion module. */
@Service
public class PromotionService {
  private final CouponRepository coupons;
  private final AuditService audit;
  private final Clock clock;

  private LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  private void changed() { if (live != null) live.area(CatalogChanged.COUPONS); }

  private AppCaches caches;

  /** Optional (plain unit tests run without it). */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  /** Creates the service (wired by Spring). */
  public PromotionService(CouponRepository coupons, AuditService audit, Clock clock) {
    this.coupons = coupons;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Trims and upper-cases a coupon code, so codes are matched regardless of how they were typed; null stays null.
   */
  public static String normalize(String code) { return code == null ? null : code.trim().toUpperCase(); }

  private static ApiException invalid(String reason) {
    return invalid(reason, null);
  }

  /**
   * The error for a coupon that cannot be used. The code stays {@code COUPON_INVALID} and {@code details.reason} is one of
   * {@code NOT_FOUND}, {@code EXPIRED}, {@code MIN_ORDER}, {@code CATEGORY}, {@code LIMIT_REACHED}, {@code FIRST_ORDER_ONLY};
   * the message says in plain words what is wrong, and for {@code MIN_ORDER} the details carry {@code minOrderPaise} and
   * {@code shortfallPaise} (how much more the customer must add) so the app can show "Add Rs 669 more".
   *
   * @param reason which rule was broken
   * @param ctx the cart the coupon was tried on, or null when only the reason is known
   */
  static ApiException invalid(String reason, CouponContext ctx) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("reason", reason);
    String message;
    switch (reason) {
      case "NOT_FOUND" -> message = "That coupon code does not exist.";
      case "EXPIRED" -> message = "This coupon is not active right now (it has ended, has not started yet or is switched off).";
      case "MIN_ORDER" -> {
        message = "This coupon needs a bigger order.";
        if (ctx != null) {
          long min = ctx.coupon().minOrderPaise();
          long base = ctx.coupon().allCategories() ? ctx.subtotalPaise() : CouponMath.base(ctx.coupon(), ctx.lines(), ctx.subtotalPaise());
          long shortfall = Math.max(0, min - base);
          details.put("minOrderPaise", min);
          details.put("shortfallPaise", shortfall);
          message = "Add " + rupees(shortfall) + " more to use this coupon (minimum order " + rupees(min) + ").";
        }
      }
      case "CATEGORY" -> message = "This coupon works only on certain categories, and none of them is in your cart.";
      case "LIMIT_REACHED" -> message = ctx != null && ctx.coupon().perUserLimit() > 0 && ctx.userUses() >= ctx.coupon().perUserLimit()
          ? "You have already used this coupon the allowed number of times." : "This coupon has been used up.";
      case "FIRST_ORDER_ONLY" -> message = "This coupon is only for your first order.";
      default -> message = "This coupon cannot be used";
    }
    return ApiException.unprocessable("COUPON_INVALID", message, details);
  }

  /** Paise as rupees for a message: {@code Rs 999}, or {@code Rs 999.50} when there are paise. */
  static String rupees(long paise) {
    return paise % 100 == 0 ? "Rs " + paise / 100 : String.format(Locale.ROOT, "Rs %d.%02d", paise / 100, paise % 100);
  }

  /**
   * What a coupon does to a cart: the discount, whether coins may still be spent in the same order, and how the
   * discount falls on each cart line (same order as the lines; lines outside the coupon's categories get 0).
   * perLine is empty when the caller does not need the split.
   */
  public record Applied(long discountPaise, boolean worksWithCoins, List<Long> perLine) {
    /** A discount without the per-line split. */
    public Applied(long discountPaise, boolean worksWithCoins) { this(discountPaise, worksWithCoins, List.of()); }
  }

  /** Discount in paise for a code on a priced cart; throws COUPON_INVALID with a reason when not usable. */
  public long discountFor(String code, UUID userId, List<PricedLine> lines, long subtotalPaise) {
    return apply(code, userId, lines, subtotalPaise, () -> 0).discountPaise();
  }

  /**
   * Checks every coupon rule and prices the discount. {@code priorOrders} counts the customer's earlier orders; it is
   * only called when the coupon is for the first order, so other coupons cost no extra query.
   */
  public Applied apply(String code, UUID userId, List<PricedLine> lines, long subtotalPaise,
                       IntSupplier priorOrders) {
    Coupon c = coupons.findByCode(normalize(code)).orElseThrow(() -> invalid("NOT_FOUND"));
    int uses = coupons.userUses(c.id(), userId);
    int prior = c.firstOrderOnly() ? priorOrders.getAsInt() : 0;
    var ctx = new CouponContext(c, clock.instant(), subtotalPaise, lines, uses, prior);
    CouponRules.firstViolation(CouponRules.STANDARD, ctx).ifPresent(r -> { throw invalid(r, ctx); });
    long discount = CouponMath.discount(c, CouponMath.base(c, lines, subtotalPaise));
    List<Long> perLine = Arrays.stream(CouponMath.perLine(c, lines, discount)).boxed().toList();
    return new Applied(discount, c.worksWithCoins(), perLine);
  }

  /**
   * Counts one use for an order. Called inside the order transaction. The per-customer limit (1 = once per customer,
   * N = N times) is checked again here under a lock on that customer and coupon, because two orders placed at the
   * same moment would both pass the check made when pricing the quote.
   */
  @Transactional
  public void consume(String code, UUID userId, UUID orderId, long discount) {
    if (code == null || code.isBlank()) return;
    Coupon c = coupons.findByCode(normalize(code)).orElseThrow(() -> invalid("NOT_FOUND"));
    if (userId != null && c.perUserLimit() > 0) {
      coupons.lockCustomerUse(c.id(), userId);
      if (coupons.userUses(c.id(), userId) >= c.perUserLimit()) throw invalid("LIMIT_REACHED");
    }
    if (coupons.takeUse(c.id()) == 0) throw invalid("LIMIT_REACHED");
    coupons.insertRedemption(c.id(), userId, orderId, discount);
  }

  /** Gives the use back when an order is cancelled or expires. */
  @Transactional
  public void release(UUID orderId) {
    coupons.openRedemptionCoupon(orderId).ifPresent(cid -> coupons.releaseRedemption(orderId, cid));
  }

  /** Coupons a customer may browse right now (no sign-in needed). Hides inactive, not-yet-started, ended and used-up ones. */
  public List<PublicCoupon> listOffered() {
    var now = clock.instant();
    // the active coupons are cached for a minute; which are on offer right now (dates, uses left) is decided on every call
    var active = caches == null ? coupons.allActive() : caches.get(AppCaches.Name.COUPONS, "active", coupons::allActive);
    return active.stream().filter(c -> c.offeredAt(now)).map(PublicCoupon::from).toList();
  }

  // ---------- admin ----------
  /** Admin: every coupon, including inactive and used-up ones. */
  public List<Coupon> list() { return coupons.all(); }

  /**
   * Admin: validates and saves a coupon, audits it and tells open screens.
   * <p>
   * Needs a code, kind FLAT or PERCENT, a positive value (at most 100 for PERCENT), non-negative limits and an end date
   * after the start. Fails with 422 when the code already exists.
   */
  @Transactional
  public Coupon create(Coupon in, UUID actor) {
    check(in);
    if (coupons.findByCode(normalize(in.code())).isPresent()) throw ApiException.validation("code", "code already exists");
    UUID id = coupons.insert(normalized(in));
    audit.log(actor, "CREATE", "coupon", id, in.code());
    changed();
    return coupons.find(id).orElseThrow();
  }

  /**
   * Admin: validates and changes a coupon with the same rules as create. Fails with 404 for an unknown coupon and 422 when the code belongs to another coupon.
   */
  @Transactional
  public Coupon update(UUID id, Coupon in, UUID actor) {
    check(in);
    coupons.find(id).orElseThrow(() -> ApiException.notFound("Coupon"));
    coupons.findByCode(normalize(in.code())).filter(x -> !x.id().equals(id))
        .ifPresent(x -> { throw ApiException.validation("code", "code already exists"); });
    coupons.update(id, normalized(in));
    audit.log(actor, "UPDATE", "coupon", id, in.code());
    changed();
    return coupons.find(id).orElseThrow();
  }

  /**
   * Admin: removes a coupon. One that has ever been used is only switched off, so past orders keep their history.
   */
  @Transactional
  public void delete(UUID id, UUID actor) {
    coupons.find(id).orElseThrow(() -> ApiException.notFound("Coupon"));
    if (coupons.redemptionCount(id) > 0) coupons.deactivate(id); else coupons.delete(id);
    audit.log(actor, "DELETE", "coupon", id, null);
    changed();
  }

  private static Coupon normalized(Coupon c) {
    return new Coupon(c.id(), normalize(c.code()), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(),
        c.categoryId(), c.perUserLimit(), c.totalLimit(), c.usedCount(), c.startAt(), c.endAt(), c.active(),
        c.worksWithCoins(), c.firstOrderOnly(), c.categoryIds());
  }

  private static void check(Coupon c) {
    if (c.code() == null || c.code().isBlank()) throw ApiException.validation("code", "code is required");
    String code = normalize(c.code());
    if (code.length() > Limits.COUPON_CODE) throw ApiException.validation("code", "at most " + Limits.COUPON_CODE + " characters");
    if (!code.matches(Limits.COUPON_CODE_REGEX)) throw ApiException.validation("code", "only letters, digits, - and _ (no spaces)");
    if (!Coupon.FLAT.equals(c.kind()) && !Coupon.PERCENT.equals(c.kind())) throw ApiException.validation("kind", "FLAT or PERCENT");
    if (c.value() <= 0) throw ApiException.validation("value", "must be positive");
    if (c.minOrderPaise() < 0) throw ApiException.validation("minOrderPaise", "must be 0 or more");
    if (c.maxDiscountPaise() < 0) throw ApiException.validation("maxDiscountPaise", "must be 0 or more");
    if (c.value() > Limits.MAX_PRICE_PAISE) throw ApiException.validation("value", "must be " + Limits.MAX_PRICE_PAISE + " or less");
    if (c.minOrderPaise() > Limits.MAX_PRICE_PAISE) throw ApiException.validation("minOrderPaise", "must be " + Limits.MAX_PRICE_PAISE + " or less");
    if (c.maxDiscountPaise() > Limits.MAX_PRICE_PAISE) throw ApiException.validation("maxDiscountPaise", "must be " + Limits.MAX_PRICE_PAISE + " or less");
    if (c.perUserLimit() < 0) throw ApiException.validation("perUserLimit", "0 for no limit, 1 for once per customer, or more");
    if (c.totalLimit() < 0) throw ApiException.validation("totalLimit", "0 for no limit, or more");
    if (Coupon.PERCENT.equals(c.kind()) && c.value() > 100) throw ApiException.validation("value", "percent cannot exceed 100");
    if (c.startAt() != null && c.endAt() != null && c.endAt().isBefore(c.startAt())) throw ApiException.validation("endAt", "must be after startAt");
  }
}
