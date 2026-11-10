package com.vijay.mall.pos.domain;

import com.vijay.mall.common.web.GstRateJson;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Turns priced cart lines and a total discount into a GST bill whose paise add up exactly. */
public final class BillCalculator {
    private BillCalculator() { }

    /**
     * One priced basket line before GST is split: the product, how it is sold, quantity (pieces, or grams for a
     * weighed item), unit price in paise and the GST percent that applies.
     */
    public record Draft(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, @GstRateJson BigDecimal gstPercent, String hsn) {
        /**
         * The line amount in paise before any discount (price times quantity, or price per 1000 g for weighed items).
         */
        public long linePaise() {
            return soldBy.lineAmount(unitPricePaise, qty);
        }
    }

    /**
     * One line of a finished bill, all money in paise: gross amount, the discount share taken off it, the net amount
     * the customer pays and the GST split of that net amount.
     */
    public record Line(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, long linePaise,
                       long discountPaise, long netPaise, @GstRateJson BigDecimal gstPercent, String hsn, TaxSplit tax) { }

    /**
     * A finished GST bill: its lines, subtotal before discount, the discount actually applied, the total payable and
     * the summed GST split.
     */
    public record Bill(List<Line> lines, long subtotalPaise, long discountPaise, long totalPaise, TaxSplit tax) { }

    /** Spreads {@code discount} (capped at the subtotal) over the lines, then splits GST on what each line really costs. */
    public static Bill build(List<Draft> drafts, long discount) {
        long[] gross = drafts.stream().mapToLong(Draft::linePaise).toArray();
        long subtotal = Arrays.stream(gross).sum();
        long applied = Math.max(0, Math.min(discount, subtotal));
        long[] net = GstCalculator.netAfterDiscount(gross, applied);
        List<Line> lines = IntStream.range(0, drafts.size()).mapToObj(i -> {
            Draft d = drafts.get(i);
            return new Line(d.productId(), d.name(), d.soldBy(), d.qty(), d.unitPricePaise(), gross[i], gross[i] - net[i],
                    net[i], d.gstPercent(), d.hsn(), GstCalculator.split(net[i], d.gstPercent()));
        }).toList();
        TaxSplit tax = lines.stream().map(Line::tax).reduce(TaxSplit.ZERO, TaxSplit::plus);
        return new Bill(lines, subtotal, applied, subtotal - applied, tax);
    }

    /**
     * Like {@link #build(List, long)}, but the coupon discount is already split per line ({@code couponByLine}, same
     * order as the drafts; lines the coupon does not cover carry 0), so a category coupon lowers only its own lines
     * and their GST. {@code otherDiscount} (coins) is then spread over what every line still costs.
     */
    public static Bill build(List<Draft> drafts, long[] couponByLine, long otherDiscount) {
        if (couponByLine.length != drafts.size()) {
            throw new IllegalArgumentException("one coupon amount per line is needed");
        }
        long[] gross = drafts.stream().mapToLong(Draft::linePaise).toArray();
        long subtotal = Arrays.stream(gross).sum();
        long[] afterCoupon = IntStream.range(0, gross.length)
                .mapToLong(i -> gross[i] - Math.min(Math.max(0, couponByLine[i]), gross[i])).toArray();
        long remaining = Arrays.stream(afterCoupon).sum();
        long other = Math.max(0, Math.min(otherDiscount, remaining));
        long[] net = GstCalculator.netAfterDiscount(afterCoupon, other);
        long applied = subtotal - Arrays.stream(net).sum();
        List<Line> lines = IntStream.range(0, drafts.size()).mapToObj(i -> {
            Draft d = drafts.get(i);
            return new Line(d.productId(), d.name(), d.soldBy(), d.qty(), d.unitPricePaise(), gross[i], gross[i] - net[i],
                    net[i], d.gstPercent(), d.hsn(), GstCalculator.split(net[i], d.gstPercent()));
        }).toList();
        TaxSplit tax = lines.stream().map(Line::tax).reduce(TaxSplit.ZERO, TaxSplit::plus);
        return new Bill(lines, subtotal, applied, subtotal - applied, tax);
    }
}
