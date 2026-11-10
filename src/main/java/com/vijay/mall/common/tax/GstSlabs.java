package com.vijay.mall.common.tax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Groups per-line GST into one slab per distinct rate. */
public final class GstSlabs {
    private GstSlabs() { }

    /**
     * One slab per distinct rate (compared numerically, {@code 18} equals {@code 18.00}), sorted by rate ascending; the amounts are the sums
     * of the splits of the lines at that rate, so the slabs add up to the document's totals exactly.
     *
     * @param rates the rate of each line (or group of lines)
     * @param splits the split of each line, in the same order as {@code rates}
     */
    public static List<GstSlab> of(List<BigDecimal> rates, List<TaxSplit> splits) {
        Map<BigDecimal, TaxSplit> byRate = new TreeMap<>();
        for (int i = 0; i < rates.size(); i++) {
            byRate.merge(GstRates.normalize(rates.get(i)), splits.get(i), TaxSplit::plus);
        }
        return byRate.entrySet().stream().map(e -> new GstSlab(e.getKey(), e.getValue().taxable(), e.getValue().cgst(),
                e.getValue().sgst(), e.getValue().tax())).toList();
    }

    /** The sum of all slabs as one split; {@code gross} is taxable plus tax. */
    public static TaxSplit total(List<GstSlab> slabs) {
        return slabs.stream().map(s -> new TaxSplit(s.taxablePaise() + s.taxPaise(), s.taxablePaise(), s.cgstPaise(), s.sgstPaise()))
                .reduce(TaxSplit.ZERO, TaxSplit::plus);
    }
}
