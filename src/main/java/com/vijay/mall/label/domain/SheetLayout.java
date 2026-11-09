package com.vijay.mall.label.domain;

import java.util.ArrayList;
import java.util.List;

/** Where each label of a print goes on the A4 pages of a SHEET template. */
public final class SheetLayout {
  private SheetLayout() { }

  /**
   * One label's place: page (1-based), column and row (0-based) and the position of its top-left corner in millimetres from the
   * page's top-left corner.
   */
  public record Placement(int page, int column, int row, double xMm, double yMm) {}

  /** How many pages {@code total} labels need when the first label goes to cell {@code startPosition} (1-based). */
  public static int pages(LabelTemplate t, int startPosition, int total) {
    return (startPosition - 1 + total + t.perPage() - 1) / t.perPage();
  }

  /**
   * Places {@code total} labels one after another, row by row, starting at cell {@code startPosition} of the first page so a
   * partly used sheet can be finished. Cell 1 is the top-left one.
   */
  public static List<Placement> place(LabelTemplate t, int startPosition, int total) {
    List<Placement> out = new ArrayList<>(total);
    for (int i = 0; i < total; i++) {
      int slot = startPosition - 1 + i;
      int within = slot % t.perPage();
      int column = within % t.columns();
      int row = within / t.columns();
      out.add(new Placement(slot / t.perPage() + 1, column, row, t.marginLeftMm() + column * (t.widthMm() + t.gapXMm()),
          t.marginTopMm() + row * (t.heightMm() + t.gapYMm())));
    }
    return out;
  }
}
