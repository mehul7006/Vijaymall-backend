package com.vijay.mall.flash.app;

import io.swagger.v3.oas.annotations.media.Schema;
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
  @Schema(name = "FlashItemView")
  public record ItemView(UUID id, UUID productId, String nameEn, long listPricePaise, Long salePricePaise, Integer discountPercent,
                         String discountKind, long discountValue, String effectiveKind, long effectiveValue, Integer maxQty, int soldQty,
                         Integer unitsLeft, Integer perCustomerLimit, int effectivePerCustomerLimit) {}

  /**
   * A whole sale as staff see it, with its items. {@code status} is {@code SCHEDULED}, {@code ACTIVE}, {@code ENDED} or
   * {@code CANCELLED}; {@code channel} is {@code ONLINE}, {@code POS} or {@code BOTH}; {@code discountKind} / {@code discountValue}
   * are the sale's default discount that items follow unless they set their own.
   */
  public record SaleView(UUID id, String name, String description, String bannerText, String status, Instant startsAt, Instant endsAt,
                         String channel, String discountKind, long discountValue, int perCustomerLimit, Instant cancelledAt,
                         UUID createdBy, Instant createdAt, Instant updatedAt, List<ItemView> items) {}

  /** A sale in a list (no items). */
  public record SaleSummary(UUID id, String name, String status, Instant startsAt, Instant endsAt, String channel, int items) {}

  /** A product in a sale, as the shop shows it. unitsLeft null = no limit; 0 = sold out (it sells at the normal price). */
  public record ShopItem(UUID productId, String nameEn, String nameGu, String imageUrl, String unitLabel, long listPricePaise,
                         long salePricePaise, int discountPercent, Integer unitsLeft, int perCustomerLimit, boolean inStock) {}

  /**
   * A running or coming sale as the shop shows it, with countdown seconds ({@code secondsToEnd} / {@code secondsToStart}, never negative).
   */
  public record ShopSale(UUID id, String name, String description, String bannerText, Instant startsAt, Instant endsAt,
                         long secondsToEnd, long secondsToStart, List<ShopItem> items) {}

  /**
   * The sales for the shop screens, with the server time they were worked out at so the app can sync its countdown.
   */
  public record ShopSales(Instant now, List<ShopSale> sales) {}
}
