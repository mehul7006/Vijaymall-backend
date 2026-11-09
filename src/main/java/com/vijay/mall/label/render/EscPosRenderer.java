package com.vijay.mall.label.render;

import static com.vijay.mall.label.render.PrinterSupport.ascii;
import static com.vijay.mall.label.render.PrinterSupport.barHeightDots;
import static com.vijay.mall.label.render.PrinterSupport.dots;

import com.google.zxing.common.BitMatrix;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.PrintJob;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * ESC/POS for receipt-width printers (58 mm and 80 mm Bluetooth or USB). Each label becomes a short strip: the text lines top to
 * bottom with the printer's own text and barcode commands, Gujarati lines as raster pictures ({@code GS v 0}), then a feed and a
 * partial cut. The output is binary: send the bytes as they are.
 */
@Component
public class EscPosRenderer {
  private static final int DPI = 203;
  private static final int[] ESC_INIT = {0x1B, 0x40};

  private final GujaratiRenderer gujarati;

  /** Creates the renderer. */
  public EscPosRenderer(GujaratiRenderer gujarati) { this.gujarati = gujarati; }

  /** Renders the job; every copy repeats the strip. */
  public byte[] render(PrintJob job) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int paperDots = dots(job.template().widthMm(), DPI);
    for (PrintJob.Entry entry : job.entries()) {
      ByteArrayOutputStream one = new ByteArrayOutputStream();
      bytes(one, ESC_INIT);
      for (LabelElement e : entry.elements()) {
        switch (e) {
          case LabelElement.Text x -> text(one, x, paperDots);
          case LabelElement.Barcode b -> barcode(one, b, paperDots);
        }
      }
      bytes(one, 0x0A, 0x1B, 0x64, 3, 0x1D, 0x56, 66, 0);
      byte[] strip = one.toByteArray();
      for (int c = 0; c < entry.copies(); c++) out.writeBytes(strip);
    }
    return out.toByteArray();
  }

  private static void bytes(ByteArrayOutputStream out, int... values) { for (int v : values) out.write(v); }

  private static int align(Alignment a) { return a == Alignment.LEFT ? 0 : a == Alignment.CENTER ? 1 : 2; }

  private void text(ByteArrayOutputStream out, LabelElement.Text t, int paperDots) {
    bytes(out, 0x1B, 0x61, align(t.align()));
    if (t.gujarati()) {
      GujaratiRenderer.Bitmap bmp = gujarati.render(t.text(), t.fontPt(), t.bold(), DPI, t.widthMm());
      byte[] bits = bmp.packed(true);
      bytes(out, 0x1D, 0x76, 0x30, 0, bmp.rowBytes() & 0xFF, bmp.rowBytes() >> 8, bmp.height() & 0xFF, bmp.height() >> 8);
      out.writeBytes(bits);
      return;
    }
    int size = t.fontPt() >= 10 ? 0x11 : 0x00;
    int charDots = size == 0 ? 12 : 24;
    String text = ascii(t.text());
    int max = Math.max(1, paperDots / charDots);
    if (text.length() > max) text = text.substring(0, max);
    bytes(out, 0x1D, 0x21, size, 0x1B, 0x45, t.bold() ? 1 : 0);
    out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));
    bytes(out, 0x0A, 0x1B, 0x45, 0, 0x1D, 0x21, 0);
  }

  private void barcode(ByteArrayOutputStream out, LabelElement.Barcode b, int paperDots) {
    BitMatrix bits = BarcodeBits.encode(b.type(), b.payload());
    bytes(out, 0x1B, 0x61, 1);
    byte[] data = ascii(b.payload()).getBytes(StandardCharsets.US_ASCII);
    if (b.type() == BarcodeType.QR) {
      int cell = Math.max(1, Math.min(8, dots(b.widthMm(), DPI) / bits.getWidth()));
      bytes(out, 0x1D, 0x28, 0x6B, 4, 0, 0x31, 0x41, 0x32, 0);
      bytes(out, 0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x43, cell);
      bytes(out, 0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x45, 0x31);
      bytes(out, 0x1D, 0x28, 0x6B, (data.length + 3) & 0xFF, (data.length + 3) >> 8, 0x31, 0x50, 0x30);
      out.writeBytes(data);
      bytes(out, 0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x51, 0x30, 0x0A);
      return;
    }
    int module = Math.max(2, Math.min(6, Math.min(paperDots, dots(b.widthMm(), DPI)) / bits.getWidth()));
    bytes(out, 0x1D, 0x68, Math.min(255, barHeightDots(b, DPI)), 0x1D, 0x77, module, 0x1D, 0x48, 2);
    if (b.type() == BarcodeType.EAN13) {
      bytes(out, 0x1D, 0x6B, 67, data.length);
      out.writeBytes(data);
    } else {
      bytes(out, 0x1D, 0x6B, 73, data.length + 2, '{', 'B');
      out.writeBytes(data);
    }
    bytes(out, 0x0A);
  }
}
