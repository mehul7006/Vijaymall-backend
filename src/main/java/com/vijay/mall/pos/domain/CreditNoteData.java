package com.vijay.mall.pos.domain;

import java.util.List;

/**
 * Everything needed to print a credit note (the receipt of a return): the shop, the note and the bill it belongs to, the
 * goods taken back, the GST share, how the refund was paid and how much of it only reduced the customer's credit.
 * All money in paise.
 */
public record CreditNoteData(
        String shopName, String address, String phone, String gstin,
        String creditNoteNo, String invoiceNo, String dateTime, String cashier, String customer, String reason,
        List<Line> lines, long taxablePaise, long cgstPaise, long sgstPaise, long refundTotalPaise,
        List<ReceiptData.PaymentLine> refunds, long creditAdjustedPaise, String footer) {

    /** One returned line: the name, how it is sold, quantity (grams for a weighed item), GST percent and the refund for it. */
    public record Line(String name, SoldBy soldBy, int qty, int gstPercent, long refundPaise) { }
}
