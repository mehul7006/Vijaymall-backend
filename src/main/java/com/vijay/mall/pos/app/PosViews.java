package com.vijay.mall.pos.app;

import java.math.BigDecimal;
import com.vijay.mall.common.web.GstRateJson;
import com.vijay.mall.inventory.domain.LotInfo;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.wallet.domain.CoinRedemption;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Inputs and outputs of the counter use cases. */
public final class PosViews {
  private PosViews() {}

  @Schema(name = "PosItem")

  /** A basket line: a product and how many pieces (1 to 9999) or, for a weighed item, grams (1 to 100000). The service checks the bound that fits the product. */
  public record Item(UUID productId, @Schema(minimum = "1", maximum = "100000") int qty) {}

  /** What is in the basket. {@code customerMobile} is needed only to use a coupon or coins, or to earn coins. */
  @Schema(name = "PosCart")
  public record Cart(List<Item> items, String customerMobile, String couponCode, CoinRedemption.Mode coinMode,
                     Integer percent) {}

  /**
   * One payment part: mode ({@code CASH}, {@code UPI}, {@code CARD} or {@code CREDIT} on a sale), amount in paise and
   * an optional reference such as a UPI transaction id or a card approval code, the optional money account the money went to
   * and, for a card machine, the terminal id.
   */
  public record Pay(@Size(max = 20) String mode,
                    @Min(value = 0, message = "must be at least 0") @Max(value = MAX_PAISE, message = "must be at most 100000000") long amountPaise,
                    @Size(max = 100) String reference,
                    @Schema(description = "Optional: id of a SUCCESS card machine payment (mode CARD or UPI, same amount, not used before)") UUID terminalPaymentId,
                    @Schema(description = "Optional: the money account the money went to; blank = the shop's default account for the mode") UUID moneyAccountId,
                    @Size(max = 60) String terminalId) {
    /** A payment part with no card machine payment, money account or terminal (the shape older clients send). */
    public Pay(String mode, long amountPaise, String reference) { this(mode, amountPaise, reference, null, null, null); }

    /** A payment part for a card machine payment, with the default account. */
    public Pay(String mode, long amountPaise, String reference, UUID terminalPaymentId) { this(mode, amountPaise, reference, terminalPaymentId, null, null); }

    /** A payment part with a money account and terminal but no card machine payment. */
    public Pay(String mode, long amountPaise, String reference, UUID moneyAccountId, String terminalId) {
      this(mode, amountPaise, reference, null, moneyAccountId, terminalId);
    }
  }

  /** The largest amount any single money input accepts: 100,000,000 paise (10 lakh rupees). */
  public static final long MAX_PAISE = 100_000_000L;

  /** A discount typed in at the till: the amount off the bill, why, and (above the cashier's limit) a manager's PIN. */
  public record ManualDiscount(@Min(value = 0, message = "must be at least 0") @Max(value = MAX_PAISE, message = "must be at most 100000000") long amountPaise,
                               @Size(max = 200) String reason, @Size(max = 20) String approverPin) {}

  /**
   * soldAt is when the bill was really rung up (set by an offline till on upload, otherwise the server's time). offline
   * says the goods have already left: stock that came up short is recorded as a shortfall instead of refusing the bill.
   */
  public record SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise, ManualDiscount manualDiscount, Instant soldAt,
                          boolean offline, Rung rung) {
    /** A bill that is priced by the server (every bill except an offline one with a rung snapshot). */
    public SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise, ManualDiscount manualDiscount, Instant soldAt,
                     boolean offline) {
      this(cart, payments, cashTenderedPaise, manualDiscount, soldAt, offline, null);
    }

    /** A bill rung now at the counter, with a manual discount and no offline snapshot. */
    public SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise, ManualDiscount manualDiscount) {
      this(cart, payments, cashTenderedPaise, manualDiscount, null, false, null);
    }

    /** A bill rung now at the counter, without a manual discount. */
    public SaleInput(Cart cart, List<Pay> payments, Long cashTenderedPaise) { this(cart, payments, cashTenderedPaise, null, null, false, null); }
  }

  /**
   * One line exactly as an offline till charged it. The server records these numbers instead of pricing the product again.
   *
   * @param productId      the product sold (must exist)
   * @param qty            pieces, or grams for a weighed item (must be positive)
   * @param unitPricePaise price per piece, or per 1000 g for a weighed item, as the till charged it (not negative)
   * @param gstPercent     GST percent the till used (one of the rates the system accepts)
   * @param discountPaise  discount the till gave on this line, in paise (default 0, must not exceed the line amount)
   * @param name           the name printed on the till's receipt (optional; the catalog name is used when blank)
   * @param linePaise      the line amount the till charged before discount (optional; when given it must equal price x quantity,
   *                       with weighed items priced per 1000 g)
   */
  public record RungLine(UUID productId, Integer qty, Long unitPricePaise, @GstRateJson BigDecimal gstPercent, Long discountPaise, String name,
                         Long linePaise) {}

  /** The bill as rung at an offline till. */
  public record Rung(List<RungLine> lines) {}

  /**
   * One priced line of a bill in paise: gross line amount, discount share, net amount and the GST percent applied.
   */
  public record BillLine(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, long linePaise,
                         long discountPaise, long netPaise, @GstRateJson BigDecimal gstPercent) {}

  /**
   * A priced basket: lines, coupon and coin discounts, round-off, total, GST split, coins that would be earned and
   * the customer's coin balance, all money in paise.
   */
  public record BillView(List<BillLine> lines, long subtotalPaise, String couponCode, long couponDiscountPaise,
                         int coinsUsed, long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise,
                         long sgstPaise, long coinsToEarn, int customerCoinBalance, List<String> warnings, long roundOffPaise,
                         long manualDiscountPaise) {
    /** A bill with no manual discount. */
    public BillView(List<BillLine> lines, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed, long coinDiscountPaise,
                    long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise, long coinsToEarn, int customerCoinBalance,
                    List<String> warnings, long roundOffPaise) {
      this(lines, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise,
          coinsToEarn, customerCoinBalance, warnings, roundOffPaise, 0);
    }

    /** totalPaise is what the customer pays (already rounded when rounding is on); roundOffPaise is the rounding that was added (can be negative). */
    public BillView(List<BillLine> lines, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed, long coinDiscountPaise,
                    long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise, long coinsToEarn, int customerCoinBalance,
                    List<String> warnings) {
      this(lines, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise,
          coinsToEarn, customerCoinBalance, warnings, 0);
    }
  }

  /** A parked basket as the cashier sees it; {@code expiresAt} is when it is removed automatically, null when baskets never expire. */
  public record HoldView(UUID id, String label, String customerMobile, UUID cashierId, Instant createdAt, Cart cart, Instant expiresAt) {}

  /** restock false = these goods are damaged or unfit to sell: they are kept apart (defective stock), not put back on the shelf. */
  public record ReturnLine(UUID saleItemId,
                           @Min(value = 1, message = "must be at least 1") @Max(value = 100000, message = "must be at most 100000") int qty, Boolean restock) {
    /** A returned line that goes back on the shelf. */
    public ReturnLine(UUID saleItemId, int qty) { this(saleItemId, qty, null); }
  }

  /**
   * The goods coming back from a bill, the reason and how the refund is paid. {@code refunds} is an explicit split;
   * {@code refundMode} (CASH, UPI or CARD) sends the whole pay-out in one mode and cannot be combined with {@code refunds}.
   * With neither, the refund goes out in the bill's own mode when it was paid in exactly one of CASH, UPI or CARD, else cash.
   */
  public record ReturnInput(List<ReturnLine> items, String reason, List<Pay> refunds, String refundMode) {
    /** A return with an explicit refund split (or none) and no refund mode. */
    public ReturnInput(List<ReturnLine> items, String reason, List<Pay> refunds) { this(items, reason, refunds, null); }
  }

  /**
   * A product as the counter sees it: names, price and MRP in paise, how it is sold, GST, HSN, item code, whether it
   * is counter-only, stock on hand, coin flags and barcodes, plus the pack size, unit, variant name and the display name
   * (name with variant and pack size, such as {@code Basmati Rice Premium 1 kg}). {@code lot} is set for a lot-priced product with
   * stock: the lot the next unit comes from, the units left in it and the {@code nextPriceChange} notice (an offline till keeps it
   * to show "after N more the price is X").
   */
  public record PosProduct(UUID productId, String name, String nameGu, long pricePaise, long mrpPaise, SoldBy soldBy,
                           @GstRateJson BigDecimal gstPercent, String hsn, String plu, boolean posOnly, int qtyOnHand, String imageUrl,
                           boolean coinRedeemable, boolean earnsCoins, List<String> barcodes, String packSize, String unit,
                           String variantName, String displayName, LotInfo lot) {
    /** A product with no lot information (no lot-priced stock). */
    public PosProduct(UUID productId, String name, String nameGu, long pricePaise, long mrpPaise, SoldBy soldBy,
                      BigDecimal gstPercent, String hsn, String plu, boolean posOnly, int qtyOnHand, String imageUrl,
                      boolean coinRedeemable, boolean earnsCoins, List<String> barcodes, String packSize, String unit,
                      String variantName, String displayName) {
      this(productId, name, nameGu, pricePaise, mrpPaise, soldBy, gstPercent, hsn, plu, posOnly, qtyOnHand, imageUrl,
          coinRedeemable, earnsCoins, barcodes, packSize, unit, variantName, displayName, null);
    }

    /** A product without pack size, unit or variant details: the display name is the plain name. */
    public PosProduct(UUID productId, String name, String nameGu, long pricePaise, long mrpPaise, SoldBy soldBy,
                      BigDecimal gstPercent, String hsn, String plu, boolean posOnly, int qtyOnHand, String imageUrl,
                      boolean coinRedeemable, boolean earnsCoins, List<String> barcodes) {
      this(productId, name, nameGu, pricePaise, mrpPaise, soldBy, gstPercent, hsn, plu, posOnly, qtyOnHand, imageUrl,
          coinRedeemable, earnsCoins, barcodes, "", "", "", name);
    }
  }

  /**
   * A scanned code resolved to a product, the quantity the code implies and the price of that quantity. {@code lot} says which lot the
   * unit will come from and, for a lot-priced product, when the price changes ({@code lot.nextPriceChange}); null without stock.
   */
  public record Scan(PosProduct product, int qty, long linePaise, LotInfo lot) {
    /** A scan with no lot information. */
    public Scan(PosProduct product, int qty, long linePaise) { this(product, qty, linePaise, null); }
  }

  /** The counter attributes of a product: how it is sold, item code, GST, HSN, counter-only flag and barcodes. */
  public record PosAttributes(UUID productId, SoldBy soldBy, String plu, @GstRateJson BigDecimal gstPercent, String hsn, boolean posOnly,
                              List<String> barcodes) {}

  /**
   * The cash-up for one day (shop time): bills, sales and GST, coins redeemed, credit notes and refunds, net sales,
   * takings and refunds by payment mode, the cash expected in the drawer, and credit given, returned and recovered.
   * All money in paise.
   */
  public record DayReport(LocalDate date, long bills, long salesPaise, long taxablePaise, long cgstPaise,
                          long sgstPaise, long coinsRedeemed, long creditNotes, long refundsPaise, long netPaise,
                          Map<String, Long> paidByMode, Map<String, Long> refundedByMode,
                          long cashExpectedPaise, long creditGivenPaise, long creditReturnedPaise,
                          long creditRecoveredPaise, Map<String, Long> creditRecoveredByMode) {}
}
