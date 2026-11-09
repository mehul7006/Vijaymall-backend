package com.vijay.mall.label.render;

import static com.vijay.mall.label.render.PrinterSupport.alignedX;
import static com.vijay.mall.label.render.PrinterSupport.ascii;
import static com.vijay.mall.label.render.PrinterSupport.barHeightDots;
import static com.vijay.mall.label.render.PrinterSupport.dots;
import static com.vijay.mall.label.render.PrinterSupport.eanBody;
import static com.vijay.mall.label.render.PrinterSupport.MM_PER_PT;
import static com.vijay.mall.label.render.PrinterSupport.moduleDots;

import com.google.zxing.common.BitMatrix;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.PrintJob;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * ZPL II for Zebra-compatible printers. Text uses the scalable font 0, barcodes the printer's own EAN-13, Code 128 and QR commands
 * and Gujarati lines are sent as a hex picture ({@code ^GFA}). The number of copies is {@code ^PQ}, so one label is described once.
 */
@Component
public class ZplRenderer {
  private final GujaratiRenderer gujarati;

  /** Creates the renderer. */
  public ZplRenderer(GujaratiRenderer gujarati) { this.gujarati = gujarati; }

  /** Renders the job as ASCII text (every character is 7-bit). */
  public byte[] render(PrintJob job) {
    LabelTemplate t = job.template();
    StringBuilder sb = new StringBuilder();
    for (PrintJob.Entry entry : job.entries()) {
      sb.append("^XA\n^CI28\n^PW").append(dots(t.widthMm(), job.dpi())).append("\n^LL").append(dots(t.heightMm(), job.dpi()))
          .append("\n^LH0,0\n");
      for (LabelElement e : entry.elements()) {
        switch (e) {
          case LabelElement.Text x -> text(sb, x, job.dpi());
          case LabelElement.Barcode b -> barcode(sb, b, job.dpi());
        }
      }
      sb.append("^PQ").append(entry.copies()).append(",0,1,N\n^XZ\n");
    }
    return sb.toString().getBytes(StandardCharsets.US_ASCII);
  }

  private void text(StringBuilder sb, LabelElement.Text t, int dpi) {
    int x = dots(t.xMm(), dpi);
    int y = dots(t.yMm(), dpi);
    if (t.gujarati()) {
      GujaratiRenderer.Bitmap bmp = gujarati.render(t.text(), t.fontPt(), t.bold(), dpi, t.widthMm());
      byte[] bits = bmp.packed(true);
      sb.append("^FO").append(alignedX(x, dots(t.widthMm(), dpi), bmp.width(), t.align())).append(',').append(y)
          .append("^GFA,").append(bits.length).append(',').append(bits.length).append(',').append(bmp.rowBytes()).append(',')
          .append(HexFormat.of().withUpperCase().formatHex(bits)).append("^FS\n");
      return;
    }
    int height = dots(t.fontPt() * MM_PER_PT, dpi);
    sb.append("^FO").append(x).append(',').append(y).append("^A0N,").append(height).append(',').append(height)
        .append("^FB").append(dots(t.widthMm(), dpi)).append(",1,0,").append(justify(t.align())).append(",0^FD").append(ascii(t.text()))
        .append("^FS\n");
  }

  private static char justify(Alignment a) { return a == Alignment.LEFT ? 'L' : a == Alignment.CENTER ? 'C' : 'R'; }

  private void barcode(StringBuilder sb, LabelElement.Barcode b, int dpi) {
    BitMatrix bits = BarcodeBits.encode(b.type(), b.payload());
    int module = moduleDots(b, bits, dpi);
    int boxW = dots(b.widthMm(), dpi);
    int x = alignedX(dots(b.xMm(), dpi), boxW, module * bits.getWidth(), Alignment.CENTER);
    int y = dots(b.yMm(), dpi);
    sb.append("^FO").append(x).append(',').append(y);
    if (b.type() == BarcodeType.QR) {
      sb.append("^BQN,2,").append(Math.min(10, module)).append("^FDQA,").append(ascii(b.payload())).append("^FS\n");
      return;
    }
    int bars = barHeightDots(b, dpi);
    sb.append("^BY").append(module).append(",3,").append(bars);
    if (b.type() == BarcodeType.EAN13) {
      sb.append("^BEN,").append(bars).append(",Y,N^FD").append(eanBody(b.payload())).append("^FS\n");
    } else {
      sb.append("^BCN,").append(bars).append(",Y,N,N^FD").append(ascii(b.payload())).append("^FS\n");
    }
  }
}
