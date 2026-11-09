package com.vijay.mall.promotion.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.promotion.domain.Coupon;
import com.vijay.mall.promotion.domain.CouponContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A coupon that cannot be used says why in plain words, and for a minimum order how much more to add. */
class CouponMessagesTest {
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  private static Coupon coupon(long minOrder, int perUserLimit, UUID category) {
    return new Coupon(UUID.randomUUID(), "FESTIVE50", "FLAT", 5000, minOrder, 0, category, perUserLimit, 0, 0, null, null, true, true, false);
  }

  private static CouponContext ctx(Coupon c, long subtotal, int userUses) {
    return new CouponContext(c, NOW, subtotal, List.of(), userUses);
  }

  @Test
  void aMinimumOrderSaysHowMuchMoreToAdd() {
    ApiException e = PromotionService.invalid("MIN_ORDER", ctx(coupon(99_900, 0, null), 33_000, 0));
    assertThat(e.code()).isEqualTo("COUPON_INVALID");
    assertThat(e.getMessage()).isEqualTo("Add Rs 669 more to use this coupon (minimum order Rs 999).");
    assertThat(e.details()).containsEntry("reason", "MIN_ORDER").containsEntry("minOrderPaise", 99_900L).containsEntry("shortfallPaise", 66_900L);
  }

  @Test
  void paiseAreShownWhenThereAreSome() {
    assertThat(PromotionService.rupees(99_950)).isEqualTo("Rs 999.50");
    assertThat(PromotionService.rupees(100)).isEqualTo("Rs 1");
    assertThat(PromotionService.rupees(5)).isEqualTo("Rs 0.05");
    ApiException e = PromotionService.invalid("MIN_ORDER", ctx(coupon(99_950, 0, null), 99_900, 0));
    assertThat(e.getMessage()).isEqualTo("Add Rs 0.50 more to use this coupon (minimum order Rs 999.50).");
  }

  @Test
  void aCategoryCouponCountsOnlyWhatIsSpentInItsCategories() {
    UUID dairy = UUID.randomUUID();
    ApiException e = PromotionService.invalid("MIN_ORDER", ctx(coupon(50_000, 0, dairy), 99_999, 0));
    assertThat(e.details()).containsEntry("shortfallPaise", 50_000L); // nothing in the cart is in the coupon's category
  }

  @Test
  void everyOtherReasonHasItsOwnPlainMessage() {
    Coupon once = coupon(0, 1, null);
    assertThat(PromotionService.invalid("NOT_FOUND", null).getMessage()).isEqualTo("That coupon code does not exist.");
    assertThat(PromotionService.invalid("EXPIRED", null).getMessage()).contains("not active right now");
    assertThat(PromotionService.invalid("CATEGORY", null).getMessage()).contains("certain categories");
    assertThat(PromotionService.invalid("FIRST_ORDER_ONLY", null).getMessage()).isEqualTo("This coupon is only for your first order.");
    assertThat(PromotionService.invalid("LIMIT_REACHED", ctx(once, 1000, 1)).getMessage()).startsWith("You have already used this coupon");
    assertThat(PromotionService.invalid("LIMIT_REACHED", ctx(once, 1000, 0)).getMessage()).isEqualTo("This coupon has been used up.");
    assertThat(PromotionService.invalid("LIMIT_REACHED", null).getMessage()).isEqualTo("This coupon has been used up.");
    assertThat(PromotionService.invalid("MIN_ORDER", null).getMessage()).isEqualTo("This coupon needs a bigger order.");
    assertThat(PromotionService.invalid("SOMETHING_NEW", null).getMessage()).isEqualTo("This coupon cannot be used");
    assertThat(PromotionService.invalid("SOMETHING_NEW", null).details()).containsEntry("reason", "SOMETHING_NEW");
  }
}
