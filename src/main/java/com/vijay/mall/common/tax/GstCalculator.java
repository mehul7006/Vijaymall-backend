package com.vijay.mall.common.tax;



import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.IntStream;

/** Inclusive-GST maths in paise, with exact (largest remainder) allocation. */
public final class GstCalculator {
    private GstCalculator() { }

    /**
     * Splits a GST-inclusive amount: taxable = gross*100/(100+rate) rounded half up; tax halves to CGST/SGST. The rate is a
     * percent with at most 3 decimals; the maths runs on the rate in thousandths of a percent, so it is exact integer arithmetic
     * and gives the same paise as the earlier whole-percent version for whole rates.
     */
    public static TaxSplit split(long gross, BigDecimal gstPercent) {
        if (gross < 0 || gstPercent.signum() < 0) {
            throw new IllegalArgumentException("negative amount or rate");
        }
        long divisor = 100_000 + GstRates.thousandths(gstPercent);
        long taxable = Math.floorDiv(Math.multiplyExact(gross, 200_000L) + divisor, 2L * divisor);
        long tax = gross - taxable;
        long cgst = tax / 2;
        return new TaxSplit(gross, taxable, cgst, tax - cgst);
    }

    /**
     * Spreads {@code amount} over {@code weights} proportionally. The result always sums to {@code amount}
     * (largest remainder; ties go to the earlier index).
     */
    public static long[] allocate(long amount, long[] weights) {
        long total = Arrays.stream(weights).sum();
        if (amount < 0 || total <= 0) {
            if (amount == 0) {
                return new long[weights.length];
            }
            throw new IllegalArgumentException("cannot allocate " + amount + " over total weight " + total);
        }
        long[] base = Arrays.stream(weights).map(w -> Math.multiplyExact(amount, w) / total).toArray();
        long[] rem = Arrays.stream(weights).map(w -> Math.multiplyExact(amount, w) % total).toArray();
        long left = amount - Arrays.stream(base).sum();
        Integer[] order = IntStream.range(0, weights.length).boxed().toArray(Integer[]::new);
        Arrays.sort(order, (a, b) -> rem[b] != rem[a] ? Long.compare(rem[b], rem[a]) : Integer.compare(a, b));
        IntStream.range(0, (int) left).forEach(k -> base[order[k]]++);
        return base;
    }

    /** Net payable per line after distributing a discount; a line never goes below zero. */
    public static long[] netAfterDiscount(long[] lines, long discount) {
        long[] d = allocate(Math.min(discount, Arrays.stream(lines).sum()), lines);
        return IntStream.range(0, lines.length).mapToLong(i -> lines[i] - d[i]).toArray();
    }

    /**
     * Net payable per line when a coupon (already split per line) and then another discount (coins, a manual discount) come off.
     * The coupon share of a line never exceeds the line; the other discount (capped at what is left) is spread over what every line
     * still costs. When the coupon has no usable split (missing, wrong size, or not adding up to {@code couponDiscount}) the whole
     * discount is spread over the lines at once.
     *
     * @param gross the line amounts before any discount
     * @param couponShares the coupon's share of each line in the same order, or {@code null} when there is no split
     * @param couponDiscount the coupon discount of the whole basket
     * @param otherDiscount coins and any other discount, spread over what every line still costs
     */
    public static long[] netPerLine(long[] gross, long[] couponShares, long couponDiscount, long otherDiscount) {
        boolean split = couponShares != null && couponShares.length == gross.length && Arrays.stream(couponShares).sum() == couponDiscount;
        if (!split) {
            return netAfterDiscount(gross, Math.max(0, Math.min(couponDiscount + otherDiscount, Arrays.stream(gross).sum())));
        }
        long[] afterCoupon = IntStream.range(0, gross.length)
                .mapToLong(i -> gross[i] - Math.min(Math.max(0, couponShares[i]), gross[i])).toArray();
        long remaining = Arrays.stream(afterCoupon).sum();
        return netAfterDiscount(afterCoupon, Math.max(0, Math.min(otherDiscount, remaining)));
    }
}
