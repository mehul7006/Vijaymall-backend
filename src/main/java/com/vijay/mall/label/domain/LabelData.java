package com.vijay.mall.label.domain;

import java.time.LocalDate;

/**
 * Everything one label can print for one variant. Blank or null values are left off the label.
 *
 * @param shopName the shop name
 * @param nameEn display name in English (name, variant and pack size)
 * @param nameGu product name in Gujarati; blank when there is none
 * @param packSize pack size text
 * @param mrpPaise maximum retail price in paise
 * @param pricePaise selling price in paise
 * @param pricePerUnit text such as {@code Rs 118.00/kg}; null when the pack size could not be read
 * @param packedOn packing date, or null
 * @param bestBefore best-before date, or null
 * @param batchNo batch number, or blank
 * @param barcodePayload the digits or text the barcode encodes
 */
public record LabelData(String shopName, String nameEn, String nameGu, String packSize, long mrpPaise, long pricePaise,
                        String pricePerUnit, LocalDate packedOn, LocalDate bestBefore, String batchNo, String barcodePayload) {}
