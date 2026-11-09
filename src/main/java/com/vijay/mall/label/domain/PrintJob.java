package com.vijay.mall.label.domain;

import java.util.List;
import java.util.UUID;

/**
 * A print request after all the data has been gathered and laid out.
 *
 * @param template the layout used
 * @param dpi printer resolution in dots per inch (thermal printer languages and picture text use it)
 * @param startPosition first cell to use on the first sheet (1-based; 1 for thermal)
 * @param entries one entry per variant, in print order
 */
public record PrintJob(LabelTemplate template, int dpi, int startPosition, List<Entry> entries) {
  /**
   * The labels of one variant.
   *
   * @param variantId the variant
   * @param name the name shown in history and errors
   * @param copies how many labels to print
   * @param elements the laid-out content of one label
   * @param barcodeType the symbology
   * @param barcode the barcode payload
   */
  public record Entry(UUID variantId, String name, int copies, List<LabelElement> elements, LabelTemplate.BarcodeType barcodeType,
                      String barcode) {}

  /** Total number of labels. */
  public int totalLabels() { return entries.stream().mapToInt(Entry::copies).sum(); }
}
