package com.vijay.mall.order.api;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.order.api.CartController.QuoteBody;
import com.vijay.mall.order.app.QuoteService;
import com.vijay.mall.order.domain.QuoteRequest;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.PublicCoupon;
import jakarta.validation.Valid;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
   */
  public record CouponOption(String code, String kind, long value, long minOrderPaise, long maxDiscountPaise, boolean applicable,
                             long discountPaise, String reason, String message, long shortfallPaise, boolean worksWithCoins) {}

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
   * message; it never hides the others.
   *
   * @param body the cart
   * @return the coupons on offer, each with what it would save or why it cannot be used
   */
  @PostMapping("/coupons")
  public List<CouponOption> options(@Valid @RequestBody QuoteBody body) {
    QuoteRequest base = body.toRequest();
    return promotions.listOffered().stream()
        .sorted(Comparator.comparing((PublicCoupon c) -> !c.categoryIds().isEmpty()).thenComparingLong(PublicCoupon::minOrderPaise))
        .limit(MAX_TRIED).map(c -> tryCoupon(c, base))
        .sorted(Comparator.comparing(CouponOption::applicable).reversed().thenComparing(Comparator.comparingLong(CouponOption::discountPaise).reversed()))
        .toList();
  }

  private CouponOption tryCoupon(PublicCoupon c, QuoteRequest base) {
    QuoteRequest withCoupon = new QuoteRequest(base.items(), c.code(), base.coinMode(), base.percent(), base.fulfilment());
    try {
      long saved = quotes.quote(CurrentUser.id(), withCoupon).couponDiscountPaise();
      return new CouponOption(c.code(), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(), true, saved, null, null, 0, c.worksWithCoins());
    } catch (ApiException e) {
      Map<String, Object> d = e.details();
      Object reason = d.get("reason");
      Object shortfall = d.get("shortfallPaise");
      return new CouponOption(c.code(), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(), false, 0,
          reason == null ? null : reason.toString(), e.getMessage(), shortfall instanceof Number n ? n.longValue() : 0, c.worksWithCoins());
    }
  }
}
