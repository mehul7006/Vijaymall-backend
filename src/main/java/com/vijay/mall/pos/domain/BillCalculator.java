package com.vijay.mall.pos.domain;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/** Turns priced cart lines and a total discount into a GST bill whose paise add up exactly. */
public final class BillCalculator {
    private BillCalculator() { }

    public record Draft(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, int gstPercent, String hsn) {
        public long linePaise() {
            return soldBy.lineAmount(unitPricePaise, qty);
        }
    }

    public record Line(UUID productId, String name, SoldBy soldBy, int qty, long unitPricePaise, long linePaise,
                       long discountPaise, long netPaise, int gstPercent, String hsn, TaxSplit tax) { }

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
}
