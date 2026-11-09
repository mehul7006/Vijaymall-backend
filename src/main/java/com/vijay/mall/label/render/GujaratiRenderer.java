package com.vijay.mall.label.render;

/** Draws Gujarati text as a picture, because no printer font has the letters and the joined forms. */
public interface GujaratiRenderer {
  /**
   * A grey picture of text: {@code gray} holds {@code width x height} bytes, row by row, 255 white to 0 black.
   */
  record Bitmap(int width, int height, byte[] gray) {
    /**
     * Packs the picture to one bit per dot, rows padded to whole bytes, most significant bit first.
     *
     * @param blackIsOne true when a set bit means ink (ZPL and ESC/POS); false for the opposite (TSPL BITMAP)
     */
    public byte[] packed(boolean blackIsOne) {
      int rowBytes = (width + 7) / 8;
      byte[] out = new byte[rowBytes * height];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          boolean black = (gray[y * width + x] & 0xFF) < 128;
          if (black == blackIsOne) out[y * rowBytes + x / 8] |= (byte) (0x80 >> (x % 8));
        }
        if (!blackIsOne) {
          for (int x = width; x < rowBytes * 8; x++) out[y * rowBytes + x / 8] |= (byte) (0x80 >> (x % 8));
        }
      }
      return out;
    }

    /** Bytes per packed row. */
    public int rowBytes() { return (width + 7) / 8; }
  }

  /** True when the font could be loaded and text can be drawn. */
  boolean available();

  /**
   * Draws the text on a white background, as tight as the text itself.
   *
   * @param text the Gujarati text
   * @param fontPt font size in points
   * @param bold synthetic bold
   * @param dpi dots per inch of the target
   * @param maxWidthMm the text is made smaller (down to half) to fit this width
   * @throws IllegalStateException when {@link #available()} is false
   */
  Bitmap render(String text, double fontPt, boolean bold, int dpi, double maxWidthMm);
}
