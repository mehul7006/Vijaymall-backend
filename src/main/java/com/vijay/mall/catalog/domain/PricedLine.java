package com.vijay.mall.catalog.domain;

import java.util.UUID;

/** One cart line priced by the server from the current catalog. */
public record PricedLine(UUID productId, UUID categoryId, String name, int qty, long unitPricePaise,
                         long lineTotalPaise, boolean coinRedeemable, boolean earnsCoins) {}
