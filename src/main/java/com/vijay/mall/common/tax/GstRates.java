package com.vijay.mall.common.tax;


import com.vijay.mall.common.error.ApiException;
import java.math.BigDecimal;
import java.util.List;

/**
 * The GST rates the system accepts and the small helpers every validator and calculator shares. A rate is a {@link BigDecimal}
 * percent (for example {@code 18} or {@code 1.5}); rates are always compared numerically ({@code 18.00} equals {@code 18}) and kept
 * with at most 3 decimals, matching the {@code numeric(6,3)} columns.
 */
public final class GstRates {
    private GstRates() { }

    /** The accepted rates, in ascending order (CBIC rates in force since 22 Sep 2025). */
    public static final List<BigDecimal> ALLOWED = List.of(new BigDecimal("0"), new BigDecimal("0.25"), new BigDecimal("1.5"),
            new BigDecimal("3"), new BigDecimal("5"), new BigDecimal("12"), new BigDecimal("18"), new BigDecimal("28"),
            new BigDecimal("40"));

    private static final String MESSAGE = "must be one of 0, 0.25, 1.5, 3, 5, 12, 18, 28 or 40";

    /** The text of the 400 error for a rate that is not accepted. */
    public static String message() { return MESSAGE; }

    /** The rate 0. */
    public static final BigDecimal ZERO = BigDecimal.ZERO;

    /** The same value without needless zeros and never in exponent form: {@code 18.00} becomes {@code 18}, {@code 1E+2} becomes {@code 100}. */
    public static BigDecimal normalize(BigDecimal rate) {
        if (rate == null) {
            return null;
        }
        BigDecimal n = rate.stripTrailingZeros();
        return n.scale() < 0 ? n.setScale(0) : n;
    }

    /** A rate from a whole percent, for example {@code of(18)}. */
    public static BigDecimal of(long wholePercent) { return BigDecimal.valueOf(wholePercent); }

    /** A rate from its decimal text, for example {@code of("1.5")}. */
    public static BigDecimal of(String text) { return normalize(new BigDecimal(text)); }

    /** Whether the rate is one of the accepted rates (compared numerically); null is not. */
    public static boolean accepts(BigDecimal rate) {
        return rate != null && ALLOWED.stream().anyMatch(a -> a.compareTo(rate) == 0);
    }

    /**
     * Checks a rate and returns it normalised.
     *
     * @param field the request field named in the error
     * @throws ApiException 400 when the rate is missing or not accepted
     */
    public static BigDecimal require(BigDecimal rate, String field) {
        if (!accepts(rate)) {
            throw ApiException.validation(field, MESSAGE);
        }
        return normalize(rate);
    }

    /** The rate in thousandths of a percent (1.5 is 1500, 40 is 40000); the rate must have at most 3 decimals. */
    public static long thousandths(BigDecimal rate) { return rate.movePointRight(3).longValueExact(); }

    /** The rate as plain text without needless zeros, for messages and receipts: {@code 18}, {@code 1.5}, {@code 0.25}. */
    public static String plain(BigDecimal rate) { return normalize(rate).toPlainString(); }
}
