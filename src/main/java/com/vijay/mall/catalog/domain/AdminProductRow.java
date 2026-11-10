package com.vijay.mall.catalog.domain;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vijay.mall.common.tax.GstRateJson;
import com.vijay.mall.common.tax.ProductGst;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * A product as the admin screen shows it: every {@link AdminProduct} field plus the GST rate and HSN the shop saved for it.
 * {@code gstConfigured} is false when nobody saved a rate yet (the product then shows 0% and no HSN), so the screen can warn.
 */
@Schema(name = "AdminProductRow")
public record AdminProductRow(@JsonUnwrapped AdminProduct product, @GstRateJson BigDecimal gstPercent, String hsn, boolean gstConfigured) {
    /** The product with its saved GST details. */
    public static AdminProductRow of(AdminProduct product, ProductGst gst) {
        return new AdminProductRow(product, gst.gstPercent(), gst.hsn(), gst.configured());
    }
}
