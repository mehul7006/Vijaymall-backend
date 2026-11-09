package com.vijay.mall.label.domain;

import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;

/** One thing drawn on a label. Positions are millimetres from the label's top-left corner. */
public sealed interface LabelElement {
  /**
   * A line of text in the box {@code x, y, width, height}.
   *
   * @param gujarati true when the text has Gujarati letters (printers draw it as a picture)
   */
  record Text(double xMm, double yMm, double widthMm, double heightMm, String text, double fontPt, boolean bold, Alignment align,
              boolean gujarati) implements LabelElement {}

  /**
   * A barcode in the box {@code x, y, width, height}; when {@code humanReadable} the digits are printed under the bars inside the box.
   */
  record Barcode(double xMm, double yMm, double widthMm, double heightMm, BarcodeType type, String payload,
                 boolean humanReadable) implements LabelElement {}
}
