package com.vijay.mall.pos.domain;

import java.util.Optional;
import java.util.stream.IntStream;

/** Pure barcode helpers: EAN checksum and weighed-label decoding. */
public final class BarcodeParser {
    private BarcodeParser() { }

    /** Decodes a scanned string. Blank input yields empty; an invalid weighed label throws. */
    public static Optional<ScannedCode> parse(String raw, String weighedPrefix, WeighedMode mode) {
        return Optional.ofNullable(raw).map(String::strip).filter(s -> !s.isEmpty()).map(code -> {
            boolean weighed = !weighedPrefix.isEmpty() && code.length() == 13 && code.startsWith(weighedPrefix)
                    && code.chars().allMatch(Character::isDigit);
            if (!weighed) {
                return (ScannedCode) new ScannedCode.Plain(code);
            }
            if (!validEan(code)) {
                throw new IllegalArgumentException("Invalid check digit on weighed label");
            }
            int p = weighedPrefix.length();
            // layout: [prefix][PLU up to digit 7][value 5 digits][check]
            String plu = code.substring(p, 7);
            int value = Integer.parseInt(code.substring(7, 12));
            return new ScannedCode.Weighed(plu, value, mode);
        });
    }

    /** True for a numeric EAN-13 / EAN-8 / UPC-A (12) with a correct check digit. */
    public static boolean validEan(String code) {
        if (code == null || !code.matches("\\d{8}|\\d{12}|\\d{13}")) {
            return false;
        }
        int n = code.length();
        int sum = IntStream.range(0, n - 1)
                .map(i -> (code.charAt(n - 2 - i) - '0') * (i % 2 == 0 ? 3 : 1))
                .sum();
        return (10 - sum % 10) % 10 == code.charAt(n - 1) - '0';
    }

    /** Computes the check digit for the digits without it. */
    public static char checkDigit(String body) {
        int n = body.length();
        int sum = IntStream.range(0, n)
                .map(i -> (body.charAt(n - 1 - i) - '0') * (i % 2 == 0 ? 3 : 1))
                .sum();
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
