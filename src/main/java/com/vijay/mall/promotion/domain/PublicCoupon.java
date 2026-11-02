package com.vijay.mall.promotion.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * What a customer may see about a coupon. It leaves out the internal id, the total limit and the used count,
 * so browsing offers does not reveal how many are left or give anything to enumerate.
 * value is paise for FLAT and a percent for PERCENT; zero means "no minimum" / "no cap".
 * worksWithCoins false means coins cannot be spent in the same order; firstOrderOnly means only a customer with no
 * earlier order can use it (the quote still decides per customer).
 */
public record PublicCoupon(String code, String kind, long value, long minOrderPaise, long maxDiscountPaise,
                           UUID categoryId, Instant startAt, Instant endAt, boolean worksWithCoins,
                           boolean firstOrderOnly) {

  public static PublicCoupon from(Coupon c) {
    return new PublicCoupon(c.code(), c.kind(), c.value(), c.minOrderPaise(), c.maxDiscountPaise(), c.categoryId(),
        c.startAt(), c.endAt(), c.worksWithCoins(), c.firstOrderOnly());
  }
}
