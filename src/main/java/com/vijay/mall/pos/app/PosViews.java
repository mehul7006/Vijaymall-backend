package com.vijay.mall.pos.app;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.wallet.domain.CoinRedemption;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Inputs and outputs of the counter use cases. */
public final class PosViews {
  private PosViews() {}

  @Schema(name = "PosItem")

  public record Item(UUID productId, int qty) {}

  /** What is in the basket. {@code customerMobile} is needed only to use a coupon or coins, or to earn coins. */
  public record Cart(List<Item> items, String customerMobile, String couponCode, CoinRedemption.Mode coinMode,
                     Integer percent) {}

  public record Pay(String mode, long amountPaise, String reference) {}

  public record SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise) {}

  public record BillLine(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, long linePaise,
                         long discountPaise, long netPaise, int gstPercent) {}

  public record BillView(List<BillLine> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
                         int coinsUsed, long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise,
                         long sgstPaise, long coinsToEarn, int customerCoinBalance, List<String> warnings) {}

  public record HoldView(UUID id, String label, String customerMobile, UUID cashierId, Instant createdAt, Cart cart) {}

  public record ReturnLine(UUID saleItemId, int qty) {}

  public record ReturnInput(List<ReturnLine> items, String reason, List<Pay> refunds) {}

  public record PosProduct(UUID productId, String name, String nameGu, long pricePaise, long mrpPaise, SoldBy soldBy,
                           int gstPercent, String hsn, String plu, boolean posOnly, int qtyOnHand, String imageUrl,
                           boolean coinRedeemable, boolean earnsCoins, List<String> barcodes) {}

  /** A scanned code resolved to a product, the quantity the code implies and the price of that quantity. */
  public record Scan(PosProduct product, int qty, long linePaise) {}

  public record PosAttributes(UUID productId, SoldBy soldBy, String plu, int gstPercent, String hsn, boolean posOnly,
                              List<String> barcodes) {}

  public record DayReport(java.time.LocalDate date, long bills, long salesPaise, long taxablePaise, long cgstPaise,
                          long sgstPaise, long coinsRedeemed, long creditNotes, long refundsPaise, long netPaise,
                          java.util.Map<String, Long> paidByMode, java.util.Map<String, Long> refundedByMode,
                          long cashExpectedPaise) {}
}
