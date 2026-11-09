package com.vijay.mall.label.render;

import static com.vijay.mall.label.render.PrinterSupport.MM_PER_PT;
import static com.vijay.mall.label.render.PrinterSupport.alignedX;
import static com.vijay.mall.label.render.PrinterSupport.ascii;
import static com.vijay.mall.label.render.PrinterSupport.barHeightDots;
import static com.vijay.mall.label.render.PrinterSupport.dots;
import static com.vijay.mall.label.render.PrinterSupport.moduleDots;

import com.google.zxing.common.BitMatrix;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.PrintJob;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * TSPL / TSPL2 for TSC and most label printers sold as "TSPL compatible". Text uses the printer's built-in fonts 1 to 5 (the largest
 * that fits the line), barcodes the printer's own commands, and Gujarati lines are sent as {@code BITMAP} pictures, so the output
 * contains raw bytes: treat the response as bytes, not as text.
 */
@Component
public class TsplRenderer {
  /** Built-in font cell sizes at 203 dpi: number, width and height in dots. */
  private static final int[][] FONTS = {{1, 8, 12}, {2, 12, 20}, {3, 16, 24}, {4, 24, 32}, {5, 32, 48}};

  private final GujaratiRenderer gujarati;

  /** Creates the renderer. */
  public TsplRenderer(GujaratiRenderer gujarati) { this.gujarati = gujarati; }

  /** Renders the job: one label design after another, each ending with {@code PRINT copies,1}. */
  public byte[] render(PrintJob job) {
    LabelTemplate t = job.template();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (PrintJob.Entry entry : job.entries()) {
      line(out, "SIZE " + mm(t.widthMm()) + " mm," + mm(t.heightMm()) + " mm");
      line(out, "GAP 2 mm,0 mm");
      line(out, "DIRECTION 1,0");
      line(out, "REFERENCE 0,0");
      line(out, "CLS");
      for (LabelElement e : entry.elements()) {
        switch (e) {
          case LabelElement.Text x -> text(out, x, job.dpi());
          case LabelElement.Barcode b -> barcode(out, b, job.dpi());
        }
      }
      line(out, "PRINT " + entry.copies() + ",1");
    }
    return out.toByteArray();
  }

  private static String mm(double v) { return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v); }

  private static void line(ByteArrayOutputStream out, String text) { out.writeBytes((text + "\r\n").getBytes(StandardCharsets.ISO_8859_1)); }

  private void text(ByteArrayOutputStream out, LabelElement.Text t, int dpi) {
    int x = dots(t.xMm(), dpi);
    int y = dots(t.yMm(), dpi);
    int boxW = dots(t.widthMm(), dpi);
    if (t.gujarati()) {
      GujaratiRenderer.Bitmap bmp = gujarati.render(t.text(), t.fontPt(), t.bold(), dpi, t.widthMm());
      out.writeBytes(("BITMAP " + alignedX(x, boxW, bmp.width(), t.align()) + "," + y + "," + bmp.rowBytes() + "," + bmp.height() + ",0,")
          .getBytes(StandardCharsets.ISO_8859_1));
      out.writeBytes(bmp.packed(false));
      out.writeBytes("\r\n".getBytes(StandardCharsets.ISO_8859_1));
      return;
    }
    int wanted = dots(t.fontPt() * MM_PER_PT, dpi);
    int[] font = FONTS[0];
    for (int[] f : FONTS) {
      if (f[2] <= wanted * 1.25) font = f;
    }
    String text = ascii(t.text());
    int maxChars = Math.max(1, boxW / font[1]);
    if (text.length() > maxChars) text = text.substring(0, maxChars);
    int left = alignedX(x, boxW, text.length() * font[1], t.align());
    line(out, "TEXT " + left + "," + y + ",\"" + font[0] + "\",0,1,1,\"" + text + "\"");
  }

  private void barcode(ByteArrayOutputStream out, LabelElement.Barcode b, int dpi) {
    BitMatrix bits = BarcodeBits.encode(b.type(), b.payload());
    int module = moduleDots(b, bits, dpi);
    int x = alignedX(dots(b.xMm(), dpi), dots(b.widthMm(), dpi), module * bits.getWidth(), Alignment.CENTER);
    int y = dots(b.yMm(), dpi);
    if (b.type() == BarcodeType.QR) {
      line(out, "QRCODE " + x + "," + y + ",M," + Math.min(10, module) + ",A,0,\"" + ascii(b.payload()) + "\"");
      return;
    }
    String name = b.type() == BarcodeType.EAN13 ? "EAN13" : "128";
    line(out, "BARCODE " + x + "," + y + ",\"" + name + "\"," + barHeightDots(b, dpi) + ",1,0," + module + "," + module * 2 + ",\""
        + ascii(b.payload()) + "\"");
  }
}
