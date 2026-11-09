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
  @Schema(name = "PosCart")
  public record Cart(List<Item> items, String customerMobile, String couponCode, CoinRedemption.Mode coinMode,
                     Integer percent) {}

  public record Pay(String mode, long amountPaise, String reference) {}

  /** A discount typed in at the till: the amount off the bill, why, and (above the cashier's limit) a manager's PIN. */
  public record ManualDiscount(long amountPaise, String reason, String approverPin) {}

  /**
   * soldAt is when the bill was really rung up (set by an offline till on upload, otherwise the server's time). offline
   * says the goods have already left: stock that came up short is recorded as a shortfall instead of refusing the bill.
   */
  public record SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise, ManualDiscount manualDiscount, java.time.Instant soldAt,
                          boolean offline) {
    public SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise, ManualDiscount manualDiscount) {
      this(cart, payments, cashTenderedPaise, manualDiscount, null, false);
    }

    public SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise) { this(cart, payments, cashTenderedPaise, null, null, false); }
  }

  public record BillLine(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, long linePaise,
                         long discountPaise, long netPaise, int gstPercent) {}

  public record BillView(List<BillLine> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
                         int coinsUsed, long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise,
                         long sgstPaise, long coinsToEarn, int customerCoinBalance, List<String> warnings, long roundOffPaise) {
    /** totalPaise is what the customer pays (already rounded when rounding is on); roundOffPaise is the rounding that was added (can be negative). */
    public BillView(List<BillLine> lines, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed, long coinDiscountPaise,
                    long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise, long coinsToEarn, int customerCoinBalance,
                    List<String> warnings) {
      this(lines, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise,
          coinsToEarn, customerCoinBalance, warnings, 0);
    }
  }

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
                          long cashExpectedPaise, long creditGivenPaise, long creditReturnedPaise,
                          long creditRecoveredPaise, java.util.Map<String, Long> creditRecoveredByMode) {}
}
