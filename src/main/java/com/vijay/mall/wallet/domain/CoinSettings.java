package com.vijay.mall.wallet.domain;

import com.vijay.mall.common.Limits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 1 coin = paisePerCoin paise; coins can pay at most maxRedeemPercent of the redeemable amount; earned coins expire after expiryMonths
 * (1 to 120).
 */
public record CoinSettings(@Min(1) @Max(Limits.MAX_PRICE_PAISE) long paisePerCoin, @Min(0) @Max(100) int maxRedeemPercent,
                           @Min(1) @Max(Limits.MAX_EXPIRY_MONTHS) int expiryMonths) {}
