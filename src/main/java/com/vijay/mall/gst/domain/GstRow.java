package com.vijay.mall.gst.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The rows of the GST and HSN master as plain records (no Spring, no JPA), shared by the matcher, the repository and the
 * service. One nested record per table.
 */
public final class GstRow {
  private GstRow() {}

  /** Table {@code gst_hsn_rate}: one HSN code under one condition (packed, loose, price band ...). */
  public record Hsn(UUID id, int rowNo, String hsn, String chapter, String tariffDescription, String productDescription,
                    String nameGu, String classificationConditions, String conditionKey, String conditionLabel,
                    BigDecimal gstPercent, BigDecimal cgstPercent, BigDecimal sgstPercent, BigDecimal igstPercent,
                    BigDecimal cessPercent, String taxType, String schedule, String notificationReference,
                    String sourceReference, LocalDate effectiveFrom, String verification, String note, boolean active,
                    Instant updatedAt) {

    /** Upper limit (inclusive, rupees) of a {@code PRICE_UPTO_n} row, else null. */
    public BigDecimal priceMax() { return bound("PRICE_UPTO_"); }

    /** Lower limit (exclusive, rupees) of a {@code PRICE_ABOVE_n} row, else null. */
    public BigDecimal priceMin() { return bound("PRICE_ABOVE_"); }

    /** True for a price-band row. */
    public boolean isPriceRow() { return priceMax() != null || priceMin() != null; }

    /** Whether this price-band row covers {@code price}; rows without a band cover every price. */
    public boolean matchesPrice(BigDecimal price) {
      BigDecimal max = priceMax();
      if (max != null) return price.compareTo(max) <= 0;
      BigDecimal min = priceMin();
      if (min != null) return price.compareTo(min) > 0;
      return true;
    }

    /** The description the API always shows: the product description, else the tariff description. */
    public String description() { return productDescription.isBlank() ? tariffDescription : productDescription; }

    private BigDecimal bound(String prefix) {
      if (conditionKey == null || !conditionKey.startsWith(prefix)) return null;
      try {
        return new BigDecimal(conditionKey.substring(prefix.length()));
      } catch (NumberFormatException e) {
        return null;
      }
    }
  }

  /** Table {@code gst_keyword}: an item name (with spellings) pointing to an HSN code. {@code conditionKey} blank = automatic. */
  public record Keyword(UUID id, int rowNo, String name, String nameGu, String keywords, String hsn, String conditionKey,
                        String department, String subcategory, String verification) {}

  /** Table {@code gst_rate_card}: one selectable rate. */
  public record Rate(UUID id, String code, int sortOrder, BigDecimal gstPercent, BigDecimal cgstPercent,
                     BigDecimal sgstPercent, BigDecimal igstPercent, BigDecimal cessPercent, String taxType,
                     String note, boolean active) {}
}
