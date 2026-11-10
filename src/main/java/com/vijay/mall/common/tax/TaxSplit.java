package com.vijay.mall.common.tax;

/** GST breakup of an inclusive amount. {@code taxable + cgst + sgst == gross}. */
public record TaxSplit(long gross, long taxable, long cgst, long sgst) {
    /** A split with no amount and no tax. */
    public static final TaxSplit ZERO = new TaxSplit(0, 0, 0, 0);

    /** Total GST: CGST plus SGST. */
    public long tax() {
        return cgst + sgst;
    }

    /** The sum of this split and another, component by component. */
    public TaxSplit plus(TaxSplit o) {
        return new TaxSplit(gross + o.gross, taxable + o.taxable, cgst + o.cgst, sgst + o.sgst);
    }
}
