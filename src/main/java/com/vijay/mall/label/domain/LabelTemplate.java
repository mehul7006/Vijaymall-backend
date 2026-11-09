package com.vijay.mall.label.domain;

import java.util.UUID;

/**
 * A saved label layout.
 *
 * <p>For a THERMAL template the size is the label itself and the grid fields are 1, 1, 0, 0, 0, 0. For a SHEET template the size is
 * one label cell and the grid places {@code columns x rows} cells on an A4 page: the first cell starts {@code marginLeftMm} from the
 * left edge and {@code marginTopMm} from the top, and cells are {@code gapXMm} / {@code gapYMm} apart.
 *
 * @param builtIn true for the templates that come with the system; they cannot be changed or deleted
 */
public record LabelTemplate(UUID id, String name, Kind kind, double widthMm, double heightMm, int columns, int rows,
                            double gapXMm, double gapYMm, double marginLeftMm, double marginTopMm, BarcodeType barcodeType,
                            LabelFields fields, boolean builtIn) {
  /** A4 page size in millimetres, the page of every SHEET template. */
  public static final double A4_WIDTH_MM = 210;
  /** A4 page height in millimetres. */
  public static final double A4_HEIGHT_MM = 297;

  /** The kind of media the template is for. */
  public enum Kind { THERMAL, SHEET }

  /** The barcode symbology printed on the label. */
  public enum BarcodeType { EAN13, CODE128, QR }

  /** How many labels one page of the template holds. */
  public int perPage() { return columns * rows; }

  /** A copy with another id, name and the built-in flag off, used when a template is created from a request. */
  public LabelTemplate withIdentity(UUID newId, String newName, boolean isBuiltIn) {
    return new LabelTemplate(newId, newName, kind, widthMm, heightMm, columns, rows, gapXMm, gapYMm, marginLeftMm, marginTopMm,
        barcodeType, fields, isBuiltIn);
  }
}
