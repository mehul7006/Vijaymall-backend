package com.vijay.mall.pos.domain;

import java.util.List;

/** Everything needed to print a bill. */
public record ReceiptData(
        String shopName, String address, String phone, String gstin,
        String invoiceNo, String dateTime, String cashier, String customer,
        List<ReceiptLine> lines,
        long subtotalPaise, long couponDiscountPaise, long coinDiscountPaise, long totalPaise,
        long taxablePaise, long cgstPaise, long sgstPaise,
        List<PaymentLine> payments, long changePaise, int coinsEarned, String footer) {

    public record PaymentLine(String mode, long amountPaise) { }
}
