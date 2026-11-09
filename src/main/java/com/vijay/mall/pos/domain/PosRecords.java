package com.vijay.mall.pos.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Plain data shapes shared by the POS layers. */
public final class PosRecords {
    private PosRecords() { }

    /**
     * The counter attributes of a product: whether it is sold by piece or by weight, its scale item code (PLU), GST
     * percent and HSN code.
     */
    public record ProductPos(UUID productId, SoldBy soldBy, String plu, int gstPercent, String hsn) {
        /** The attributes of a product nobody configured: sold by piece, no item code, 0% GST, no HSN. */
        public static ProductPos defaults(UUID productId) {
            return new ProductPos(productId, SoldBy.UNIT, null, 0, "");
        }
    }

    /**
     * The shop's own details and bill settings: name, address, phone, GSTIN, GST state code, UPI id, invoice prefix,
     * receipt footer, weighing-scale prefix and mode, plus the tagline ({@code promise}) and the "about the shop"
     * text ({@code details}) shown in the apps, a {@code shortName} for tight spaces and the optional Gujarati versions
     * of the name, address, promise and details (empty means the apps fall back to English).
     */
    public record ShopSetting(String shopName, String address, String phone, String gstin, String stateCode,
                              String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                              WeighedMode weighedMode, String promise, String details, String shortName, String shopNameGu,
                              String addressGu, String promiseGu, String detailsGu) {
        /** A shop setting with the English texts only (no short name and no Gujarati texts). */
        public ShopSetting(String shopName, String address, String phone, String gstin, String stateCode,
                           String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                           WeighedMode weighedMode, String promise, String details) {
            this(shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix,
                weighedMode, promise, details, "", "", "", "", "");
        }

        /** A shop setting with no "about the shop" text. */
        public ShopSetting(String shopName, String address, String phone, String gstin, String stateCode,
                           String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                           WeighedMode weighedMode, String promise) {
            this(shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix,
                weighedMode, promise, "");
        }

        /** A shop setting with no customer-facing promise line. */
        public ShopSetting(String shopName, String address, String phone, String gstin, String stateCode,
                           String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                           WeighedMode weighedMode) {
            this(shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix,
                weighedMode, "");
        }
    }

    /** One stored payment on a bill or refund: mode, amount in paise and reference. */
    public record PaymentRecord(String mode, long amountPaise, String reference, UUID terminalPaymentId) {
        /** A payment without a card machine payment. */
        public PaymentRecord(String mode, long amountPaise, String reference) { this(mode, amountPaise, reference, null); }
    }

    /**
     * One stored line of a bill, money in paise: gross, discount, net and the GST split, plus how many units have
     * been returned so far. {@code qty} is pieces, or grams for a weighed item.
     */
    public record SaleItemRecord(UUID id, UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise,
                                 long linePaise, long discountPaise, long netPaise, int gstPercent, String hsn,
                                 long taxablePaise, long cgstPaise, long sgstPaise, int returnedQty) {
        /** Units that can still be returned: sold minus already returned. */
        public int returnableQty() {
            return qty - returnedQty;
        }
    }

    /**
     * A stored counter bill: invoice number, who rang it and for which customer, amounts and GST split in paise,
     * coupon, coin and manual discounts, round-off, payments (including any credit part), coins earned and the time
     * it was sold. For an offline bill the time is when it was really rung up.
     */
    public record SaleRecord(UUID id, String invoiceNo, UUID cashierId, UUID customerId, String customerMobile,
                             long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                             long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                             int coinsEarned, Long tenderedPaise, long changePaise, Instant createdAt,
                             List<SaleItemRecord> items, List<PaymentRecord> payments, String paymentType,
                             long manualDiscountPaise, String manualDiscountReason, UUID manualDiscountApprover,
                             long roundOffPaise) {
        /** A bill that was not rounded. */
        public SaleRecord(UUID id, String invoiceNo, UUID cashierId, UUID customerId, String customerMobile,
                          long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                          long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                          int coinsEarned, Long tenderedPaise, long changePaise, Instant createdAt,
                          List<SaleItemRecord> items, List<PaymentRecord> payments, String paymentType,
                          long manualDiscountPaise, String manualDiscountReason, UUID manualDiscountApprover) {
            this(id, invoiceNo, cashierId, customerId, customerMobile, subtotalPaise, couponCode, couponDiscountPaise,
                coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise, coinsEarned,
                tenderedPaise, changePaise, createdAt, items, payments, paymentType, manualDiscountPaise, manualDiscountReason,
                manualDiscountApprover, 0);
        }

        /** A bill with no manual discount. */
        public SaleRecord(UUID id, String invoiceNo, UUID cashierId, UUID customerId, String customerMobile,
                          long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                          long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                          int coinsEarned, Long tenderedPaise, long changePaise, Instant createdAt,
                          List<SaleItemRecord> items, List<PaymentRecord> payments, String paymentType) {
            this(id, invoiceNo, cashierId, customerId, customerMobile, subtotalPaise, couponCode, couponDiscountPaise,
                coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise, coinsEarned,
                tenderedPaise, changePaise, createdAt, items, payments, paymentType, 0, "", null);
        }

        /** A bill whose payment type is worked out from its payments. */
        public SaleRecord(UUID id, String invoiceNo, UUID cashierId, UUID customerId, String customerMobile,
                          long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                          long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                          int coinsEarned, Long tenderedPaise, long changePaise, Instant createdAt,
                          List<SaleItemRecord> items, List<PaymentRecord> payments) {
            this(id, invoiceNo, cashierId, customerId, customerMobile, subtotalPaise, couponCode, couponDiscountPaise,
                coinsUsed, coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise, coinsEarned,
                tenderedPaise, changePaise, createdAt, items, payments, PaymentRules.classify(lines(payments)));
        }

        /** What the customer put on credit with this bill (0 when none). */
        public long creditPaise() {
            return payments.stream().filter(p -> PaymentRules.CREDIT.equals(p.mode())).mapToLong(PaymentRecord::amountPaise).sum();
        }
    }

    /** Converts stored payments into the shape {@link PaymentRules} checks. */
    static List<PaymentRules.Line> lines(List<PaymentRecord> payments) {
        return payments.stream().map(p -> new PaymentRules.Line(p.mode(), p.amountPaise(), p.reference())).toList();
    }

    /**
     * One returned line of a credit note: which sold line, how many units, the refund for it and that refund's GST
     * split, in paise.
     */
    public record ReturnItemRecord(UUID saleItemId, int qty, long refundPaise, long taxablePaise, long cgstPaise,
                                   long sgstPaise) { }

    /**
     * A stored credit note for goods taken back from a bill: its number, reason, refund total and GST split in paise,
     * coins clawed back or restored, the lines, how the refund was paid out and how much of it only reduced the
     * customer's credit balance.
     */
    public record ReturnRecord(UUID id, String creditNoteNo, UUID saleId, UUID cashierId, String reason,
                               long refundPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                               int coinsClawedBack, int coinsRestored, Instant createdAt,
                               List<ReturnItemRecord> items, List<PaymentRecord> refunds, long creditAdjustedPaise) {
        /** A return that did not touch customer credit. */
        public ReturnRecord(UUID id, String creditNoteNo, UUID saleId, UUID cashierId, String reason,
                            long refundPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                            int coinsClawedBack, int coinsRestored, Instant createdAt,
                            List<ReturnItemRecord> items, List<PaymentRecord> refunds) {
            this(id, creditNoteNo, saleId, cashierId, reason, refundPaise, taxablePaise, cgstPaise, sgstPaise,
                coinsClawedBack, coinsRestored, createdAt, items, refunds, 0);
        }
    }

    /** What earlier returns of one bill already moved, so a new return only moves its own share. */
    public record ReturnedSoFar(long grossPaise, int coinsClawedBack, int coinsRestored) { }

    /**
     * A basket parked at the till: who parked it, an optional label, the customer mobile, and the basket stored as
     * JSON text.
     */
    public record HoldRecord(UUID id, UUID cashierId, String label, String customerMobile, String payload,
                             Instant createdAt) { }
}
