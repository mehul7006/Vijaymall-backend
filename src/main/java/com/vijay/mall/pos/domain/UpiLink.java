package com.vijay.mall.pos.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds a UPI deep link (rendered as QR by the app). */
public final class UpiLink {
    private UpiLink() { }

    /**
     * Builds a {@code upi://pay} deep link (shown as a QR code by the app) that asks for an exact amount.
     *
     * @param upiId the shop's UPI id (payee address)
     * @param payeeName name shown to the payer
     * @param amountPaise amount in paise
     * @param note transaction note shown to the payer (may be null)
     * @throws IllegalArgumentException if the UPI id is blank
     */
    public static String build(String upiId, String payeeName, long amountPaise, String note) {
        if (upiId == null || upiId.isBlank()) {
            throw new IllegalArgumentException("UPI id not configured");
        }
        return "upi://pay?pa=" + enc(upiId.strip())
                + "&pn=" + enc(payeeName)
                + "&am=" + Money.plain(amountPaise)
                + "&cu=INR&tn=" + enc(note);
    }

    /** URL-encodes a value, using {@code %20} for spaces. */
    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
