package com.vijay.mall.pos.domain;

import java.util.List;

/**
 * Everything needed to print the receipt of a credit repayment: the shop, receipt number, customer, how the money was
 * paid and the credit balance before and after. All money in paise.
 */
public record RepaymentData(
        String shopName, String address, String phone, String gstin,
        String receiptNo, String dateTime, String cashier, String customer, long amountPaise,
        List<ReceiptData.PaymentLine> payments, long balanceBeforePaise, long balanceAfterPaise, String note, String footer) { }
