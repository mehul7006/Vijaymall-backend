package com.vijay.mall.order.api;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.api.CartController.QuoteBody;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.Quote;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.PublicCoupon;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Which coupons can I use on this cart, and what would each save?" for the coupon picker on the cart screen. Each coupon on
 * offer is tried on the cart exactly as the quote would try it, so what is shown here is what the quote will give. Any
 * signed-in customer may call it; nothing is stored.
 */
@RestController
@RequestMapping("/api/v1/cart")
public class CartCouponController {
  /** Coupons tried per call: every one is a full quote, so the picker shows at most this many (the likeliest to apply come first). */
  static final int MAX_TRIED = 20;

  /**
   * One coupon tried on the cart.
   *
   * @param code the coupon code
   * @param kind {@code FLAT} or {@code PERCENT}
   * @param value paise for {@code FLAT}, a percent for {@code PERCENT}
   * @param minOrderPaise the minimum order in paise (0 = none)
   * @param maxDiscountPaise the cap on the discount in paise (0 = none)
   * @param applicable whether the coupon can be used on this cart now
   * @param discountPaise what it would save on this cart in paise (0 when not applicable)
   * @param reason when not applicable: {@code EXPIRED}, {@code MIN_ORDER}, {@code CATEGORY}, {@code LIMIT_REACHED} or {@code FIRST_ORDER_ONLY}
   * @param message when not applicable: what is wrong, in plain words, ready to show
   * @param shortfallPaise for {@code MIN_ORDER}: how much more must be added to use it, in paise
   * @param worksWithCoins false when coins cannot be spent in the same order as this coupon
   * @param appliesTo what the coupon is for: {@code ALL}, {@code CATEGORIES}, {@code ITEMS} or {@code MIXED}
   * @param appliesToText the categories and items it is for, as a sentence part ("Dairy and Basmati Rice 5 kg"); empty for {@code ALL}
   * @param coveredLines when applicable: the cart lines the discount lands on, with the share each one gets (the shares add up to
   *     {@code discountPaise}); lines the coupon does not cover are not listed
   */
  public record CouponOption(String code, String kind, long value, long minOrderPaise, long maxDiscountPaise, boolean applicable,
                             long discountPaise, String reason, String message, long shortfallPaise, boolean worksWithCoins,
                             String appliesTo, String appliesToText, List<CoveredLine> coveredLines) {
    /** An option that does not say what the coupon is for. */
    public CouponOption(String code, String kind, long value, long minOrderPaise, long maxDiscountPaise, boolean applicable,
                        long discountPaise, String reason, String message, long shortfallPaise, boolean worksWithCoins) {
      this(code, kind, value, minOrderPaise, maxDiscountPaise, applicable, discountPaise, reason, message, shortfallPaise,
          worksWithCoins, "ALL", "", List.of());
    }
  }

  /** One cart line a coupon discounts: the product, what the line costs and the part of the discount it receives, in paise. */
  public record CoveredLine(UUID productId, String name, long lineTotalPaise, long discountPaise) {}

  private final QuoteService quotes;
  private final PromotionService promotions;

  /**
   * Creates the controller.
   *
   * @param quotes prices a cart
   * @param promotions lists the coupons on offer
   */
  public CartCouponController(QuoteService quotes, PromotionService promotions) {
    this.quotes = quotes;
    this.promotions = promotions;
  }

  /**
   * Tries every coupon on offer on the posted cart (the same body as the quote; any {@code couponCode} in it is ignored).
   * Usable coupons come first, the biggest saving first; the others follow with the reason and, for a minimum order, how much
   * more to add. At most {@value #MAX_TRIED} coupons are tried (each is a full quote): those open to every category and with the
   * lowest minimum order are chosen first. A coupon that fails for any business reason is listed as not applicable with the plain
   * message; it never hides the others. A cart that cannot be priced at all (an unknown or unavailable product, a bad quantity) is
   * refused with the same 400 the quote gives, instead of listing every coupon as not applicable.
   *
   * @param body the cart
   * @return the coupons on offer, each with what it would save or why it cannot be used
   */
  @PostMapping("/coupons")
  public List<CouponOption> options(@Valid @RequestBody QuoteBody body) {
    QuoteRequest base = body.toRequest();
    quotes.quote(CurrentUser.id(), new QuoteRequest(base.items(), null, base.coinMode(), base.percent(), base.fulfilment()));
    return promotions.listOffered().stream()
        .sorted(Comparator.comparing((PublicCoupon c) -> !"ALL".equals(c.appliesTo())).thenComparingLong(PublicCoupon::minOrderPaise))
        .limit(MAX_TRIED).map(c -> tryCoupon(c, base))
        .sorted(Comparator.comparing(CouponOption::applicable).reversed().thenComparing(Comparator.comparingLong(CouponOption::discountPaise).reversed()))
        .toList();
  }

  private CouponOption tryCoupon(PublicCoupon c, QuoteRequest base) {
    QuoteRequest withCoupon = new QuoteRequest(base.items(), c.code(), base.coinMode(), base.percent(), base.fulfilment());
    try {
      Quote quote = quotes.quote(CurrentUser.id(), withCoupon);
      List<CoveredLine> covered = quote.lines().stream().filter(l -> l.couponDiscountPaise() > 0)
          .map(l -> new CoveredLine(l.productId(), l.name(), l.lineTotalPaise(), l.couponDiscountPaise())).toList();
      return new CouponOption(c.code(), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(), true,
          quote.couponDiscountPaise(), null, null, 0, c.worksWithCoins(), c.appliesTo(), c.appliesToText(), covered);
    } catch (ApiException e) {
      Map<String, Object> d = e.details();
      Object reason = d.get("reason");
      Object shortfall = d.get("shortfallPaise");
      return new CouponOption(c.code(), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(), false, 0,
          reason == null ? null : reason.toString(), e.getMessage(), shortfall instanceof Number n ? n.longValue() : 0, c.worksWithCoins(),
          c.appliesTo(), c.appliesToText(), List.of());
    }
  }
}
