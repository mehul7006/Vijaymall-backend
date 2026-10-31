package com.vijay.mall.pos.domain;

/** Result of interpreting a scanned barcode. */
public sealed interface ScannedCode {

    /** Ordinary pack barcode: look it up exactly. */
    record Plain(String code) implements ScannedCode { }

    /** Scale label: item code (PLU) plus either grams or a price in paise. */
    record Weighed(String plu, int value, WeighedMode mode) implements ScannedCode { }
}
