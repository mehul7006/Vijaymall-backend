package com.vijay.mall.wallet.domain;

/** 1 coin = paisePerCoin paise; coins can pay at most maxRedeemPercent of the redeemable amount. */
public record CoinSettings(long paisePerCoin, int maxRedeemPercent, int expiryMonths) {}
