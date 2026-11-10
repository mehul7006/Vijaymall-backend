package com.vijay.mall.common.tax;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;

/**
 * One GST slab of a document: everything sold at one rate, in paise. {@code taxPaise} is CGST plus SGST, and
 * {@code taxablePaise + taxPaise} is what the customer paid for the lines of this rate.
 */
@Schema(name = "GstSlab")
public record GstSlab(@GstRateJson BigDecimal gstPercent, long taxablePaise, long cgstPaise, long sgstPaise, long taxPaise) { }
