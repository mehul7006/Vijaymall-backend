package com.vijay.mall.pos.domain;

/** One printed line of a bill. */
public record ReceiptLine(String name, SoldBy soldBy, int qty, long unitPricePaise, long netPaise, int gstPercent) { }
