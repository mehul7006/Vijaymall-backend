package com.vijay.mall.pos.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Builds a UPI deep link (rendered as QR by the app). */
public final class UpiLink {
    private UpiLink() { }

    public static String build(String upiId, String payeeName, long amountPaise, String note) {
        if (upiId == null || upiId.isBlank()) {
            throw new IllegalArgumentException("UPI id not configured");
        }
        return "upi://pay?pa=" + enc(upiId.strip())
                + "&pn=" + enc(payeeName)
                + "&am=" + Money.plain(amountPaise)
                + "&cu=INR&tn=" + enc(note);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
