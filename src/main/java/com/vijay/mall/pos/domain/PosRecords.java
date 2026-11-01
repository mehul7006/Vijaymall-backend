package com.vijay.mall.pos.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Plain data shapes shared by the POS layers. */
public final class PosRecords {
    private PosRecords() { }

    public record ProductPos(UUID productId, SoldBy soldBy, String plu, int gstPercent, String hsn) {
        public static ProductPos defaults(UUID productId) {
            return new ProductPos(productId, SoldBy.UNIT, null, 0, "");
        }
    }

    public record ShopSetting(String shopName, String address, String phone, String gstin, String stateCode,
                              String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                              WeighedMode weighedMode, String promise) {
        /** A shop setting with no customer-facing promise line. */
        public ShopSetting(String shopName, String address, String phone, String gstin, String stateCode,
                           String upiId, String invoicePrefix, String receiptFooter, String weighedPrefix,
                           WeighedMode weighedMode) {
            this(shopName, address, phone, gstin, stateCode, upiId, invoicePrefix, receiptFooter, weighedPrefix,
                weighedMode, "");
        }
    }

    public record PaymentRecord(String mode, long amountPaise, String reference) { }

    public record SaleItemRecord(UUID id, UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise,
                                 long linePaise, long discountPaise, long netPaise, int gstPercent, String hsn,
                                 long taxablePaise, long cgstPaise, long sgstPaise, int returnedQty) {
        public int returnableQty() {
            return qty - returnedQty;
        }
    }

    public record SaleRecord(UUID id, String invoiceNo, UUID cashierId, UUID customerId, String customerMobile,
                             long subtotalPaise, String couponCode, long couponDiscountPaise, int coinsUsed,
                             long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                             int coinsEarned, Long tenderedPaise, long changePaise, Instant createdAt,
                             List<SaleItemRecord> items, List<PaymentRecord> payments) { }

    public record ReturnItemRecord(UUID saleItemId, int qty, long refundPaise, long taxablePaise, long cgstPaise,
                                   long sgstPaise) { }

    public record ReturnRecord(UUID id, String creditNoteNo, UUID saleId, UUID cashierId, String reason,
                               long refundPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                               int coinsClawedBack, int coinsRestored, Instant createdAt,
                               List<ReturnItemRecord> items, List<PaymentRecord> refunds) { }

    /** What earlier returns of one bill already moved, so a new return only moves its own share. */
    public record ReturnedSoFar(long grossPaise, int coinsClawedBack, int coinsRestored) { }

    public record HoldRecord(UUID id, UUID cashierId, String label, String customerMobile, String payload,
                             Instant createdAt) { }
}
