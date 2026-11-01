package com.vijay.mall.catalog.domain;

import java.util.UUID;

/**
 * What customers see. Packs of one product (250 g, 500 g, 1 kg) are separate rows that share productGroupId.
 * rating is the average of reviews (0 when there are none).
 */
public record Product(UUID id, UUID categoryId, String nameEn, String nameGu, String description,
                      long mrpPaise, long pricePaise, String unit, String packSize, String imageUrl,
                      boolean inStock, int qtyAvailable, boolean hotSale, boolean earnsCoins,
                      boolean coinRedeemable, UUID productGroupId, double rating, int reviewCount) {}
