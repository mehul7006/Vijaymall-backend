package com.vijay.mall.pos.domain;

import java.util.List;

/** Everything needed to print a bill. */
public record ReceiptData(
        String shopName, String address, String phone, String gstin,
        String invoiceNo, String dateTime, String cashier, String customer,
        List<ReceiptLine> lines,
        long subtotalPaise, long couponDiscountPaise, long coinDiscountPaise, long totalPaise,
        long taxablePaise, long cgstPaise, long sgstPaise,
        List<PaymentLine> payments, long changePaise, int coinsEarned, String footer, long manualDiscountPaise, long roundOffPaise) {

    /** A receipt with no manual discount. */
    public ReceiptData(String shopName, String address, String phone, String gstin, String invoiceNo, String dateTime,
                       String cashier, String customer, List<ReceiptLine> lines, long subtotalPaise, long couponDiscountPaise,
                       long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                       List<PaymentLine> payments, long changePaise, int coinsEarned, String footer) {
        this(shopName, address, phone, gstin, invoiceNo, dateTime, cashier, customer, lines, subtotalPaise, couponDiscountPaise,
                coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise, payments, changePaise, coinsEarned, footer, 0, 0);
    }

    /** A receipt that was not rounded. */
    public ReceiptData(String shopName, String address, String phone, String gstin, String invoiceNo, String dateTime,
                       String cashier, String customer, List<ReceiptLine> lines, long subtotalPaise, long couponDiscountPaise,
                       long coinDiscountPaise, long totalPaise, long taxablePaise, long cgstPaise, long sgstPaise,
                       List<PaymentLine> payments, long changePaise, int coinsEarned, String footer, long manualDiscountPaise) {
        this(shopName, address, phone, gstin, invoiceNo, dateTime, cashier, customer, lines, subtotalPaise, couponDiscountPaise,
                coinDiscountPaise, totalPaise, taxablePaise, cgstPaise, sgstPaise, payments, changePaise, coinsEarned, footer,
                manualDiscountPaise, 0);
    }

    /** One payment printed on the receipt: mode and amount in paise. */
    public record PaymentLine(String mode, long amountPaise) { }
}
