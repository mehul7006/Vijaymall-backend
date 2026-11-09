package com.vijay.mall.label.render;

import com.google.zxing.common.BitMatrix;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelFields.Alignment;

/** Small calculations the printer-language renderers share. */
final class PrinterSupport {
  /** Height of the digits printed under a barcode, in millimetres (kept inside the barcode box). */
  static final double DIGIT_BOX_MM = 2.7;
  /** Millimetres per typographic point. */
  static final double MM_PER_PT = 25.4 / 72;

  private PrinterSupport() { }

  static int dots(double mm, int dpi) { return (int) Math.round(mm * dpi / 25.4); }

  /** Printable ASCII only; the quote, caret, tilde and backslash that printer languages treat as commands become a question mark, like every other character outside printable ASCII. */
  static String ascii(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    text.codePoints().forEach(c -> sb.append(c < 0x20 || c > 0x7E || c == '"' || c == '^' || c == '~' || c == '\\' ? '?' : (char) c));
    return sb.toString();
  }

  /** Whole dots per narrow bar so that the barcode is no wider than its box; never below 1. */
  static int moduleDots(LabelElement.Barcode b, BitMatrix bits, int dpi) { return Math.max(1, dots(b.widthMm(), dpi) / bits.getWidth()); }

  /** Height in dots of the bars alone (the digits take their own space under them). */
  static int barHeightDots(LabelElement.Barcode b, int dpi) {
    return Math.max(8, dots(b.heightMm() - (b.humanReadable() ? DIGIT_BOX_MM : 0), dpi));
  }

  /** Left edge in dots of content of the given width inside a box, for the alignment. */
  static int alignedX(int boxX, int boxW, int contentW, Alignment align) {
    int free = Math.max(0, boxW - contentW);
    return boxX + switch (align) {
      case LEFT -> 0;
      case CENTER -> free / 2;
      case RIGHT -> free;
    };
  }

  /** The barcode payload as the printer needs it: an EAN-13 without its check digit is 12 digits. */
  static String eanBody(String payload) { return payload.substring(0, 12); }
}
