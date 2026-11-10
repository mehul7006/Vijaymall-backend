package com.vijay.mall.pos.domain;

import com.vijay.mall.common.tax.GstRateJson;
import java.math.BigDecimal;

/** One printed line of a bill. */
public record ReceiptLine(String name, SoldBy soldBy, int qty, long unitPricePaise, long netPaise, @GstRateJson BigDecimal gstPercent) { }
