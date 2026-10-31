package com.vijay.mall.pos.domain;

/** GST breakup of an inclusive amount. {@code taxable + cgst + sgst == gross}. */
public record TaxSplit(long gross, long taxable, long cgst, long sgst) {
    public static final TaxSplit ZERO = new TaxSplit(0, 0, 0, 0);

    public long tax() {
        return cgst + sgst;
    }

    public TaxSplit plus(TaxSplit o) {
        return new TaxSplit(gross + o.gross, taxable + o.taxable, cgst + o.cgst, sgst + o.sgst);
    }
}
