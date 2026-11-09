package com.vijay.mall.pos.domain;

import java.util.UUID;

/**
 * One difference found while recording an offline bill.
 *
 * @param kind     what kind of difference
 * @param detail   a sentence for the manager
 * @param productId the product it is about (null for a whole-bill difference such as a manual discount)
 * @param expected what the system expected (a price or amount in paise, a GST percent, stock on hand), as text
 * @param actual   what the till did, as text
 */
public record OfflineFinding(OfflineExceptionKind kind, String detail, UUID productId, String expected, String actual) { }
