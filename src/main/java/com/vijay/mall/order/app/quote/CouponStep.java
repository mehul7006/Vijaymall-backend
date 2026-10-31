package com.vijay.mall.order.app.quote;

import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import com.vijay.mall.promotion.app.PromotionService;
import org.springframework.stereotype.Component;

@Component
public class CouponStep implements QuoteStep {
  private final PromotionService promotions;

  public CouponStep(PromotionService promotions) { this.promotions = promotions; }

  @Override public int order() { return 200; }

  @Override
  public void apply(QuoteContext c) {
    String code = c.request.couponCode();
    if (code == null || code.isBlank()) return;
    c.couponCode = PromotionService.normalize(code);
    c.couponDiscount = Math.min(promotions.discountFor(code, c.userId, c.lines, c.subtotal), c.subtotal);
  }
}
