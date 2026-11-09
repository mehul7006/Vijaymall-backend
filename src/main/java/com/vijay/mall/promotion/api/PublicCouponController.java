package com.vijay.mall.promotion.api;

import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.PublicCoupon;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public offers list, so customers can browse coupons before signing in. Applying one still goes through /cart/quote. */
@RestController
@RequestMapping("/api/v1/coupons")
public class PublicCouponController {
  private final PromotionService promotions;

  /** Creates the controller (wired by Spring). */
  public PublicCouponController(PromotionService promotions) { this.promotions = promotions; }

  /** Lists the coupons on offer right now: active, inside their dates and not used up. No sign-in needed. */
  @GetMapping
  public List<PublicCoupon> list() { return promotions.listOffered(); }
}
