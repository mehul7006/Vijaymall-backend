package com.vijay.mall.catalog.domain;

import java.time.Instant;
import java.util.UUID;

/** Product plus the fields only the shop owner sees. hotSale here is the raw flag. */
public record AdminProduct(UUID id, UUID categoryId, String nameEn, String nameGu, String description,
                           long mrpPaise, long pricePaise, String unit, String packSize, String imageUrl,
                           boolean inStock, int qtyAvailable, boolean hotSale, boolean earnsCoins,
                           boolean coinRedeemable, int qtyOnHand, int lowThreshold, boolean active,
                           Instant hotStart, Instant hotEnd, UUID productGroupId, double rating, int reviewCount) {

  /**
   * Whether the product counts as a hot-sale item at the given moment.
   * <p>The raw {@code hotSale} flag only applies inside the optional {@code hotStart} / {@code hotEnd} window (both ends
   * inclusive; a missing end means open-ended).
   * @param now the instant to test, normally {@code clock.instant()}
   * @return true when the flag is on and {@code now} is inside the window
   */
  public boolean effectiveHot(Instant now) {
    return hotSale && (hotStart == null || !now.isBefore(hotStart)) && (hotEnd == null || !now.isAfter(hotEnd));
  }

  /**
   * Drops the owner-only fields and returns what a shopper sees, with {@code hotSale} resolved through {@link #effectiveHot}.
   * <p>The returned product has no flash-sale offer; the catalog service adds that separately.
   * @param now the instant used to decide whether the hot-sale window is open
   * @return the customer view of this product
   */
  public Product toCustomer(Instant now) {
    return new Product(id, categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit, packSize,
        imageUrl, inStock, qtyAvailable, effectiveHot(now), earnsCoins, coinRedeemable, productGroupId, rating, reviewCount);
  }
}
