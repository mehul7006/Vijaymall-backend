package com.vijay.mall.pos.domain;

import com.vijay.mall.common.web.GstRateJson;
import java.math.BigDecimal;
import java.util.List;

/**
 * Everything needed to print a credit note (the receipt of a return): the shop, the note and the bill it belongs to, the
 * goods taken back, the GST share, how the refund was paid and how much of it only reduced the customer's credit.
 * {@code couponSharePaise} is the coupon discount that left with the returned goods (not refunded) and {@code couponClawbackPaise}
 * coupon discount taken back from the refund by the shop's return policy. All money in paise.
 */
public record CreditNoteData(
        String shopName, String address, String phone, String gstin,
        String creditNoteNo, String invoiceNo, String dateTime, String cashier, String customer, String reason,
        List<Line> lines, long taxablePaise, long cgstPaise, long sgstPaise, long refundTotalPaise,
        List<ReceiptData.PaymentLine> refunds, long creditAdjustedPaise, String footer,
        long couponSharePaise, long couponClawbackPaise) {

    /** A credit note of a bill without coupon (no coupon share, nothing taken back). */
    public CreditNoteData(String shopName, String address, String phone, String gstin,
            String creditNoteNo, String invoiceNo, String dateTime, String cashier, String customer, String reason,
            List<Line> lines, long taxablePaise, long cgstPaise, long sgstPaise, long refundTotalPaise,
            List<ReceiptData.PaymentLine> refunds, long creditAdjustedPaise, String footer) {
        this(shopName, address, phone, gstin, creditNoteNo, invoiceNo, dateTime, cashier, customer, reason, lines, taxablePaise,
            cgstPaise, sgstPaise, refundTotalPaise, refunds, creditAdjustedPaise, footer, 0, 0);
    }


    /** One returned line: the name, how it is sold, quantity (grams for a weighed item), GST percent and the refund for it. */
    public record Line(String name, SoldBy soldBy, int qty, @GstRateJson BigDecimal gstPercent, long refundPaise) { }
}
