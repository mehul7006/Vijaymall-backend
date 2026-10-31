package com.vijay.mall.promotion.domain;

import com.vijay.mall.catalog.domain.PricedLine;
import java.time.Instant;
import java.util.List;

public record CouponContext(Coupon coupon, Instant now, long subtotalPaise, List<PricedLine> lines, int userUses) {}
