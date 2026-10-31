package com.vijay.mall.pos.domain;

/** Paise to rupee text. */
public final class Money {
    private Money() { }

    /** "1234.50" */
    public static String plain(long paise) {
        String sign = paise < 0 ? "-" : "";
        long a = Math.abs(paise);
        return "%s%d.%02d".formatted(sign, a / 100, a % 100);
    }
}
