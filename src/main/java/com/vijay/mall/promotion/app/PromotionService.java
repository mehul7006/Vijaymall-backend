package com.vijay.mall.promotion.app;

import com.vijay.mall.catalog.domain.PricedLine;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.Limits;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.promotion.domain.AppliesTo;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.CouponContext;
import com.vijay.mall.promotion.domain.CouponMath;
import com.vijay.mall.promotion.domain.CouponRules;
import com.vijay.mall.promotion.domain.PublicCoupon;
import com.vijay.mall.promotion.infra.CouponRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

  private RuntimeSettings settings;

  /** Optional (plain unit tests run without it): the admin switch for coupons on flash sale lines. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  /** Whether a coupon may also discount flash sale lines: setting {@code coupon.allowOnFlashItems}, on unless the admin turned it off. */
  boolean allowOnFlash() { return settings == null || settings.getBool(Settings.COUPON_ALLOW_ON_FLASH); }

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
   * {@code NOT_FOUND}, {@code EXPIRED}, {@code MIN_ORDER}, {@code CATEGORY}, {@code ITEM}, {@code FLASH_ITEMS}, {@code LIMIT_REACHED},
   * {@code FIRST_ORDER_ONLY};
   * the message says in plain words what is wrong, and for {@code MIN_ORDER} the details carry {@code minOrderPaise} and
   * {@code shortfallPaise} (how much more the customer must add) so the app can show "Add Rs 669 more".
   *
   * @param reason which rule was broken
   * @param ctx the cart the coupon was tried on, or null when only the reason is known
   */
  static ApiException invalid(String reason, CouponContext ctx) { return invalid(reason, ctx, AppliesTo.EVERYTHING); }

  /** As {@link #invalid(String, CouponContext)}, with the names the coupon is for, so the message can say what it is for. */
  static ApiException invalid(String reason, CouponContext ctx, AppliesTo applies) {
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
          long base = ctx.coveredPaise();
          long shortfall = Math.max(0, min - base);
          details.put("minOrderPaise", min);
          details.put("shortfallPaise", shortfall);
          message = "Add " + rupees(shortfall) + " more to use this coupon (minimum order " + rupees(min) + ").";
        }
      }
      case "CATEGORY" -> message = applies.text().isEmpty()
          ? "This coupon works only on certain categories, and none of them is in your cart."
          : "This coupon is for " + applies.text() + "; none are in your cart.";
      case "ITEM" -> message = applies.text().isEmpty()
          ? "This coupon works only on certain items, and none of them is in your cart."
          : "This coupon is for " + applies.text() + "; none are in your cart.";
      case "FLASH_ITEMS" -> message = "This coupon does not apply to items that are already on a flash sale.";
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
   * perLine is empty when the caller does not need the split. minOrderPaise is the coupon's minimum order, kept on the order for
   * the return policy.
   */
  public record Applied(long discountPaise, boolean worksWithCoins, List<Long> perLine, long minOrderPaise) {
    /** A discount with its per-line split, for a coupon without a minimum order. */
    public Applied(long discountPaise, boolean worksWithCoins, List<Long> perLine) { this(discountPaise, worksWithCoins, perLine, 0); }

    /** A discount without the per-line split. */
    public Applied(long discountPaise, boolean worksWithCoins) { this(discountPaise, worksWithCoins, List.of(), 0); }
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
    boolean onFlash = allowOnFlash();
    var ctx = new CouponContext(c, clock.instant(), subtotalPaise, lines, uses, prior, onFlash);
    CouponRules.firstViolation(CouponRules.STANDARD, ctx).ifPresent(r -> { throw invalid(r, ctx, namesFor(r, c)); });
    long discount = CouponMath.discount(c, CouponMath.base(c, lines, subtotalPaise, onFlash));
    List<Long> perLine = Arrays.stream(CouponMath.perLine(c, lines, discount, onFlash)).boxed().toList();
    return new Applied(discount, c.worksWithCoins(), perLine, c.minOrderPaise());
  }

  /** The names a failure message may need: only looked up for the reasons that say what the coupon is for. */
  private AppliesTo namesFor(String reason, Coupon c) {
    if (!reason.equals("CATEGORY") && !reason.equals("ITEM")) return AppliesTo.EVERYTHING;
    return coupons.namesFor(List.of(c)).of(c);
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
    // the active coupons, with the names of what they are for, are cached for a minute; which are on offer right now
    // (dates, uses left) is decided on every call
    var active = caches == null ? activeWithNames() : caches.get(AppCaches.Name.COUPONS, "active", this::activeWithNames);
    return active.stream().filter(o -> o.coupon().offeredAt(now)).map(o -> PublicCoupon.from(o.coupon(), o.applies())).toList();
  }

  /** A coupon with what it is for. */
  record Offer(Coupon coupon, AppliesTo applies) {}

  private List<Offer> activeWithNames() {
    List<Coupon> active = coupons.allActive();
    var names = coupons.namesFor(active);
    return active.stream().map(c -> new Offer(c, names.of(c))).toList();
  }

  // ---------- admin ----------
  /** Admin: every coupon, including inactive and used-up ones. */
  public List<Coupon> list() { return coupons.all(); }

  /** A coupon for the admin screen: the coupon, what it is for (with names) and warnings about items that are switched off or gone. */
  public record AdminView(Coupon coupon, AppliesTo appliesTo, List<String> warnings) {}

  /** Admin: every coupon with the names of what it is for, in a fixed number of queries however many coupons there are. */
  public List<AdminView> listWithNames() {
    List<Coupon> all = coupons.all();
    var names = coupons.namesFor(all);
    return all.stream().map(c -> view(c, names)).toList();
  }

  /** Admin: one coupon with names, or 404. */
  public AdminView detail(UUID id) {
    Coupon c = coupons.find(id).orElseThrow(() -> ApiException.notFound("Coupon"));
    return view(c, coupons.namesFor(List.of(c)));
  }

  private static AdminView view(Coupon c, CouponRepository.Names names) {
    AppliesTo applies = names.of(c);
    List<String> warnings = new ArrayList<>();
    applies.products().stream().filter(n -> !n.active())
        .forEach(n -> warnings.add(n.name() + " is switched off, so it cannot be bought until it is back on sale"));
    applies.productGroups().stream().filter(n -> !n.active())
        .forEach(n -> warnings.add("Every variant of " + n.name() + " is switched off"));
    return new AdminView(c, applies, List.copyOf(warnings));
  }

  /**
   * Admin: validates and saves a coupon, audits it and tells open screens.
   * <p>
   * Needs a code, kind FLAT or PERCENT, a positive value (at most 100 for PERCENT), non-negative limits and an end date
   * after the start. Fails with 422 when the code already exists.
   */
  @Transactional
  public Coupon create(Coupon in, UUID actor) {
    check(in);
    checkTargets(in);
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
    checkTargets(in);
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
        c.worksWithCoins(), c.firstOrderOnly(), c.categoryIds(), c.productIds(), c.productGroupIds());
  }

  /**
   * Checks the ids a coupon lists: at most {@value Coupon#MAX_ITEMS} products and product groups together, and every category,
   * product and product group must exist (400 naming the field and position, for example {@code productIds[2]}). A product that
   * exists but is switched off is accepted; the admin view warns about it.
   */
  private void checkTargets(Coupon c) {
    if (c.productIds().size() + c.productGroupIds().size() > Coupon.MAX_ITEMS) {
      throw ApiException.validation("productIds", "at most " + Coupon.MAX_ITEMS + " products and product groups per coupon");
    }
    unknown("categoryIds", c.categoryIds(), coupons.existingCategories(c.categoryIds()));
    unknown("productIds", c.productIds(), coupons.existingProducts(c.productIds()));
    unknown("productGroupIds", c.productGroupIds(), coupons.existingGroups(c.productGroupIds()));
  }

  private static void unknown(String field, List<UUID> ids, Set<UUID> known) {
    for (int i = 0; i < ids.size(); i++) {
      if (!known.contains(ids.get(i))) throw ApiException.validation(field + "[" + i + "]", "unknown id " + ids.get(i));
    }
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
