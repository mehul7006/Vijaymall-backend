package com.vijay.mall.label.domain;

/**
 * Which fields a template prints and how.
 *
 * @param shopName print the shop name
 * @param productName which language(s) of the product name to print
 * @param packSize print the pack size ("500 g")
 * @param mrp print the maximum retail price
 * @param price print the selling price
 * @param packedOn print the packing date when the print request gives one
 * @param bestBefore print the best-before date when given
 * @param batchNo print the batch number when given
 * @param pricePerUnit print the price per kg, litre or piece when the pack size can be read
 * @param fontSize base font size in points (4 to 24); the price line is two points bigger
 * @param alignment alignment of the text lines
 */
public record LabelFields(boolean shopName, NameLanguage productName, boolean packSize, boolean mrp, boolean price, boolean packedOn,
                          boolean bestBefore, boolean batchNo, boolean pricePerUnit, int fontSize, Alignment alignment) {
  /** Smallest base font size in points. */
  public static final int MIN_FONT = 4;
  /** Largest base font size in points. */
  public static final int MAX_FONT = 24;

  /** Which product name language is printed. */
  public enum NameLanguage { NONE, EN, GU, BOTH }

  /** Alignment of the text lines. */
  public enum Alignment { LEFT, CENTER, RIGHT }

  /** Fills in the defaults for values a request left out: no name and centred text. */
  public LabelFields {
    if (productName == null) productName = NameLanguage.NONE;
    if (alignment == null) alignment = Alignment.CENTER;
  }
}
