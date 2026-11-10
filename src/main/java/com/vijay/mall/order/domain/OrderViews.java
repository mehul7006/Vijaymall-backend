package com.vijay.mall.order.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vijay.mall.common.tax.GstRateJson;
import com.vijay.mall.common.tax.GstRates;
import com.vijay.mall.common.tax.GstSlab;
import com.vijay.mall.common.tax.GstSlabs;
import com.vijay.mall.common.tax.TaxSplit;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.payment.domain.PaymentSession;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Shapes returned by the order endpoints (see API contract). */
public final class OrderViews {
  private OrderViews() {}

  /**
   * One order line as it was at order time, so it still reads right after the product is edited.
   * @param imageUrl the product picture when the order was placed; null for lines whose product had none
   * @param gstPercent the GST rate of the line when the order was placed; null for an order placed before GST was recorded
   * @param hsn the HSN code at order time (may be empty); null when not recorded
   * @param taxablePaise the line's taxable value after its coupon and coin share; null when not recorded
   * @param cgstPaise half of the line's GST (the even half); null when not recorded
   * @param sgstPaise the other half (it gets the odd paisa); null when not recorded
   */
  @Schema(name = "OrderItem")
  public record Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise, String nameGu,
                     String unitLabel, long couponDiscountPaise, String imageUrl, @GstRateJson BigDecimal gstPercent, String hsn,
                     Long taxablePaise, Long cgstPaise, Long sgstPaise) {
    /** A line whose GST was not recorded. */
    public Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise, String nameGu,
                String unitLabel, long couponDiscountPaise, String imageUrl) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, nameGu, unitLabel, couponDiscountPaise, imageUrl, null, null, null,
          null, null);
    }

    /** Without the picture snapshot; couponDiscountPaise is this line's share of the order's coupon discount. */
    public Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise, String nameGu,
                String unitLabel, long couponDiscountPaise) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, nameGu, unitLabel, couponDiscountPaise, null);
    }

    /** Without coupon share and picture. */
    public Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise, String nameGu,
                String unitLabel) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, nameGu, unitLabel, 0);
    }

    /** A line without Gujarati name, pack label or coupon share. */
    public Item(UUID productId, String name, int qty, long unitPricePaise, long lineTotalPaise) {
      this(productId, name, qty, unitPricePaise, lineTotalPaise, name, "");
    }
  }

  /**
   * The GST of a whole order, added up from its lines (prices include GST; the delivery fee is not part of it). For an order placed before
   * GST was recorded {@code gstRecorded} is false, the totals are null and {@code gstSlabs} is empty: nothing is guessed for old orders.
   * Otherwise taxable plus tax equals the amount paid minus the delivery fee.
   * @param gstRecorded whether the order's lines carry their GST
   * @param taxableTotalPaise sum of the taxable values
   * @param cgstTotalPaise sum of CGST
   * @param sgstTotalPaise sum of SGST
   * @param taxTotalPaise CGST plus SGST
   * @param gstSlabs one entry per rate, lowest first
   */
  @Schema(name = "OrderGst")
  public record Gst(boolean gstRecorded, Long taxableTotalPaise, Long cgstTotalPaise, Long sgstTotalPaise, Long taxTotalPaise,
                    List<GstSlab> gstSlabs) {
    /** An order whose GST was never recorded. */
    public static final Gst NOT_RECORDED = new Gst(false, null, null, null, null, List.of());

    /** The GST made of these slabs. */
    public static Gst of(List<GstSlab> slabs) {
      TaxSplit t = GstSlabs.total(slabs);
      return new Gst(true, t.taxable(), t.cgst(), t.sgst(), t.tax(), slabs);
    }

    /** The GST of these order lines; {@link #NOT_RECORDED} when there are none or any line has no recorded GST. */
    public static Gst ofItems(List<Item> items) {
      if (items.isEmpty() || items.stream().anyMatch(i -> i.taxablePaise() == null)) return NOT_RECORDED;
      return of(GstSlabs.of(items.stream().map(Item::gstPercent).toList(), items.stream()
          .map(i -> new TaxSplit(i.taxablePaise() + i.cgstPaise() + i.sgstPaise(), i.taxablePaise(), i.cgstPaise(), i.sgstPaise())).toList()));
    }
  }

  /**
   * One entry of an order's timeline.
   * @param status the status that was reached
   * @param at when
   */
  public record Step(String status, Instant at) {}

  /**
   * One row of the customer's order list.
   * @param totalPaise amount to pay, in paise
   * @param itemCount total units on the order
   * @param cancelledBy CUSTOMER, SHOP or SYSTEM once cancelled, else null
   * @param cancelCode why it was cancelled (see {@link View}), else null
   */
  @Schema(name = "OrderSummary")

  public record Summary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                        Instant placedAt, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                        long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus,
                        String cancelledBy, String cancelCode, @JsonUnwrapped Gst gst) {
    /** Without the GST of the order (not recorded). */
    public Summary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                   Instant placedAt, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                   long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus,
                   String cancelledBy, String cancelCode) {
      this(id, orderNo, status, fulfilment, totalPaise, itemCount, placedAt, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, paymentMethod, paymentStatus, cancelledBy,
          cancelCode, Gst.NOT_RECORDED);
    }

    /** Without who cancelled the order and why. */
    public Summary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                   Instant placedAt, long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                   long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus) {
      this(id, orderNo, status, fulfilment, totalPaise, itemCount, placedAt, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, paymentMethod, paymentStatus, null, null);
    }
  }

  /** Same money fields as {@link Summary} (and as {@link View}), so the customer and the shop always read the same numbers. */
  public record AdminSummary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                             Instant placedAt, String customerName, String customerEmail, String customerMobile,
                             long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                             long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus,
                             String cancelledBy, String cancelCode, @JsonUnwrapped Gst gst) {
    /** Without the GST of the order (not recorded). */
    public AdminSummary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                        Instant placedAt, String customerName, String customerEmail, String customerMobile,
                        long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                        long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus,
                        String cancelledBy, String cancelCode) {
      this(id, orderNo, status, fulfilment, totalPaise, itemCount, placedAt, customerName, customerEmail,
          customerMobile, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise,
          deliveryFeePaise, paymentMethod, paymentStatus, cancelledBy, cancelCode, Gst.NOT_RECORDED);
    }

    /** Without who cancelled the order and why. */
    public AdminSummary(UUID id, String orderNo, String status, String fulfilment, long totalPaise, int itemCount,
                        Instant placedAt, String customerName, String customerEmail, String customerMobile,
                        long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                        long coinDiscountPaise, long deliveryFeePaise, String paymentMethod, String paymentStatus) {
      this(id, orderNo, status, fulfilment, totalPaise, itemCount, placedAt, customerName, customerEmail,
          customerMobile, subtotalPaise, couponCode, couponDiscountPaise, coinsUsed, coinDiscountPaise,
          deliveryFeePaise, paymentMethod, paymentStatus, null, null);
    }
  }

  /**
   * The customer's full view of one order.
   * @param paymentSession how to pay, only while an online order waits for payment
   * @param timeline status changes, oldest first
   * @param slot the delivery or pickup window the customer chose
   * @param cancelReason why it was cancelled or rejected, if it was
   * @param coinBalance the customer's current coin balance
   * @param paymentExpiresAt when an unpaid online order is cancelled; only set while it waits for payment
   * @param cancelledBy CUSTOMER, SHOP or SYSTEM once cancelled, else null
   * @param cancelCode CUSTOMER_CANCELLED, REJECTED_BY_SHOP, CANCELLED_BY_SHOP, PAYMENT_TIMEOUT or PAYMENT_FAILED; null when not cancelled
   */
  @Schema(name = "OrderView")
  public record View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                     String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                     long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                     long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                     List<Step> timeline, Instant placedAt, String slot, String cancelReason, Integer coinBalance,
                     Instant paymentExpiresAt, String cancelledBy, String cancelCode, @JsonUnwrapped Gst gst) {
    /** Without the GST of the order (not recorded). */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt, String slot, String cancelReason, Integer coinBalance,
                Instant paymentExpiresAt, String cancelledBy, String cancelCode) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, slot, cancelReason, coinBalance, paymentExpiresAt, cancelledBy, cancelCode,
          Gst.NOT_RECORDED);
    }

    /** Without who cancelled the order and why. */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt, String slot, String cancelReason, Integer coinBalance,
                Instant paymentExpiresAt) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, slot, cancelReason, coinBalance, paymentExpiresAt, null, null);
    }

    /** Without the time the customer has left to pay (only set while an online order waits for payment). */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt, String slot, String cancelReason, Integer coinBalance) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, slot, cancelReason, coinBalance, null);
    }

    /** Without the customer's current coin balance. */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt, String slot, String cancelReason) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, slot, cancelReason, null);
    }

    /** Without slot, cancel reason, coin balance and payment deadline. */
    public View(UUID id, String orderNo, String status, String fulfilment, String paymentMethod,
                String paymentStatus, List<Item> items, long subtotalPaise, String couponCode,
                long couponDiscountPaise, int coinsUsed, long coinDiscountPaise, long deliveryFeePaise,
                long totalPaise, int coinsEarned, Address address, PaymentSession paymentSession,
                List<Step> timeline, Instant placedAt) {
      this(id, orderNo, status, fulfilment, paymentMethod, paymentStatus, items, subtotalPaise, couponCode,
          couponDiscountPaise, coinsUsed, coinDiscountPaise, deliveryFeePaise, totalPaise, coinsEarned, address,
          paymentSession, timeline, placedAt, null, null, null);
    }
  }

  /** An order as staff see it: the full order plus the customer's name and contact details. */
  public record AdminView(@JsonUnwrapped View order, String customerName, String customerEmail, String customerMobile) {}
}
