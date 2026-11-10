package com.vijay.mall.common.tax;

import java.math.BigDecimal;

/**
 * The GST rate and HSN code of a product as the shop saved them. {@code configured} is false for a product nobody gave a rate: it then
 * carries the default (0%, no HSN), so a screen can warn that the real rate is still to be set.
 */
public record ProductGst(@GstRateJson BigDecimal gstPercent, String hsn, boolean configured) {
    /** The default of a product with no saved counter details: 0%, no HSN, not configured. */
    public static final ProductGst UNSET = new ProductGst(GstRates.ZERO, "", false);
}
