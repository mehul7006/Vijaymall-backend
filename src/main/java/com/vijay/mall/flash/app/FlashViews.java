package com.vijay.mall.flash.app;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the flash sale endpoints return. Money is paise. */
public final class FlashViews {
  private FlashViews() {}

  /**
   * One product in a sale, as staff see it. discountKind/discountValue are the item's own override (null kind = follows the sale);
   * effectiveKind/effectiveValue is what really applies. salePricePaise is worked out from the product's current list price.
   */
  public record ItemView(UUID id, UUID productId, String nameEn, long listPricePaise, Long salePricePaise, Integer discountPercent,
                         String discountKind, long discountValue, String effectiveKind, long effectiveValue, Integer maxQty, int soldQty,
                         Integer unitsLeft, Integer perCustomerLimit, int effectivePerCustomerLimit) {}

  public record SaleView(UUID id, String name, String description, String bannerText, String status, Instant startsAt, Instant endsAt,
                         String channel, String discountKind, long discountValue, int perCustomerLimit, Instant cancelledAt,
                         UUID createdBy, Instant createdAt, Instant updatedAt, List<ItemView> items) {}

  /** A sale in a list (no items). */
  public record SaleSummary(UUID id, String name, String status, Instant startsAt, Instant endsAt, String channel, int items) {}

  /** A product in a sale, as the shop shows it. unitsLeft null = no limit; 0 = sold out (it sells at the normal price). */
  public record ShopItem(UUID productId, String nameEn, String nameGu, String imageUrl, String unitLabel, long listPricePaise,
                         long salePricePaise, int discountPercent, Integer unitsLeft, int perCustomerLimit, boolean inStock) {}

  public record ShopSale(UUID id, String name, String description, String bannerText, Instant startsAt, Instant endsAt,
                         long secondsToEnd, long secondsToStart, List<ShopItem> items) {}

  public record ShopSales(Instant now, List<ShopSale> sales) {}
}
