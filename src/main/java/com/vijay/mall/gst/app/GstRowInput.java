package com.vijay.mall.gst.app;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Editable fields of a master row, shared by create and update. On create a missing optional field takes its default; on update
 * a missing optional field keeps the stored value ({@code hsn}, {@code description}, {@code gstPercent} and {@code taxType} are
 * always required).
 *
 * @param hsn the code, 4, 6 or 8 digits
 * @param description the product description shown to staff
 * @param nameGu Gujarati name, optional
 * @param gstPercent total (IGST) rate, 0 to 100 with at most 2 decimals; CGST and SGST are half each
 * @param cessRate cess in percent, optional
 * @param taxType {@code TAXABLE}, {@code NIL}, {@code EXEMPT} or {@code NON_GST}
 * @param condition condition key such as {@code PACKED_LABELLED}; optional (default {@code ANY})
 * @param conditionLabel text for the condition, optional
 * @param verification {@code VERIFIED} or {@code VERIFY_PRODUCT}; optional (default {@code VERIFY_PRODUCT})
 * @param notificationReference the notification the rate comes from, optional
 * @param effectiveFrom date the rate starts, optional (default today)
 * @param note free text, optional
 * @param keywords item names ADDED under this HSN code; never removed by an edit
 */
public record GstRowInput(String hsn, String description, String nameGu, BigDecimal gstPercent, BigDecimal cessRate, String taxType,
                          String condition, String conditionLabel, String verification, String notificationReference,
                          LocalDate effectiveFrom, String note, List<String> keywords) {}
