package com.vijay.mall.pos.domain;

import java.util.Arrays;
import java.util.stream.IntStream;

/** Inclusive-GST maths in paise, with exact (largest remainder) allocation. */
public final class GstCalculator {
    private GstCalculator() { }

    /** Splits a GST-inclusive amount: taxable = gross*100/(100+rate) rounded half up; tax halves to CGST/SGST. */
    public static TaxSplit split(long gross, int gstPercent) {
        if (gross < 0 || gstPercent < 0) {
            throw new IllegalArgumentException("negative amount or rate");
        }
        long taxable = Math.floorDiv(gross * 100 * 2 + (100 + gstPercent), 2L * (100 + gstPercent));
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
}
