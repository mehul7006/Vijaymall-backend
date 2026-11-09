package com.vijay.mall.order.app.quote;

import com.vijay.mall.order.domain.QuoteContext;
import com.vijay.mall.order.domain.QuoteStep;
import com.vijay.mall.order.infra.OrderRepository;
import com.vijay.mall.promotion.app.PromotionService;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CouponStep implements QuoteStep {
  private final PromotionService promotions;
  private final OrderRepository orders;

  public CouponStep(PromotionService promotions, OrderRepository orders) {
    this.promotions = promotions;
    this.orders = orders;
  }

  @Override public int order() { return 200; }

  @Override
  public void apply(QuoteContext c) {
    String code = c.request.couponCode();
    if (code == null || code.isBlank()) return;
    c.couponCode = PromotionService.normalize(code);
    PromotionService.Applied applied = promotions.apply(code, c.userId, c.lines, c.subtotal,
        () -> c.userId == null ? 0 : (int) orders.activeOrderCount(c.userId));
    c.couponDiscount = Math.min(applied.discountPaise(), c.subtotal);
    c.couponByLine = applied.perLine() == null ? List.of() : applied.perLine();
    c.couponBlocksCoins = !applied.worksWithCoins();
  }
}
