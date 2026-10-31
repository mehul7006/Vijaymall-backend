package com.vijay.mall.catalog.domain;

import java.util.UUID;

/** What customers see. */
public record Product(UUID id, UUID categoryId, String nameEn, String nameGu, String description,
                      long mrpPaise, long pricePaise, String unit, String packSize, String imageUrl,
                      boolean inStock, int qtyAvailable, boolean hotSale, boolean earnsCoins,
                      boolean coinRedeemable) {}
