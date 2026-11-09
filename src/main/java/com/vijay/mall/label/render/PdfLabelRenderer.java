package com.vijay.mall.label.render;

import com.google.zxing.common.BitMatrix;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.domain.PrintJob;
import com.vijay.mall.label.domain.SheetLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

/**
 * Writes the labels as a vector PDF: text in Helvetica, barcodes as filled bars and squares, Gujarati as a 300 dpi picture. Each
 * variant's label is drawn once as a form and stamped once per copy, so 500 labels stay small and quick. A THERMAL template makes
 * one page of the label's size per label; a SHEET template fills A4 pages cell by cell, beginning at the start position.
 */
@Component
public class PdfLabelRenderer {
  private static final double PT_PER_MM = 72 / 25.4;
  private static final int PICTURE_DPI = 300;
  private static final double DIGIT_PT = 7;
  private static final double DIGIT_BOX_MM = 2.7;

  private final GujaratiRenderer gujarati;

  /** Creates the renderer. */
  public PdfLabelRenderer(GujaratiRenderer gujarati) { this.gujarati = gujarati; }

  /** Renders the whole job. */
  public byte[] render(PrintJob job) {
    LabelTemplate t = job.template();
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      doc.getDocumentInformation().setTitle("Labels");
      PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      Map<String, PDImageXObject> pictures = new HashMap<>();
      List<PDAppearanceStream> forms = job.entries().stream().map(e -> form(doc, t, e.elements(), regular, bold, pictures)).toList();
      if (t.kind() == Kind.THERMAL) {
        for (int i = 0; i < forms.size(); i++) {
          for (int c = 0; c < job.entries().get(i).copies(); c++) {
            PDPage page = new PDPage(new PDRectangle((float) (t.widthMm() * PT_PER_MM), (float) (t.heightMm() * PT_PER_MM)));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
              cs.drawForm(forms.get(i));
            }
          }
        }
      } else {
        sheet(doc, job, forms);
      }
      doc.save(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void sheet(PDDocument doc, PrintJob job, List<PDAppearanceStream> forms) throws IOException {
    LabelTemplate t = job.template();
    List<SheetLayout.Placement> places = SheetLayout.place(t, job.startPosition(), job.totalLabels());
    int pages = SheetLayout.pages(t, job.startPosition(), job.totalLabels());
    double pageH = LabelTemplate.A4_HEIGHT_MM * PT_PER_MM;
    int next = 0;
    for (int p = 1; p <= pages; p++) {
      PDPage page = new PDPage(new PDRectangle((float) (LabelTemplate.A4_WIDTH_MM * PT_PER_MM), (float) pageH));
      doc.addPage(page);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        while (next < places.size() && places.get(next).page() == p) {
          int entry = entryOf(job, next);
          SheetLayout.Placement at = places.get(next);
          cs.saveGraphicsState();
          cs.transform(Matrix.getTranslateInstance((float) (at.xMm() * PT_PER_MM), (float) (pageH - (at.yMm() + t.heightMm()) * PT_PER_MM)));
          cs.drawForm(forms.get(entry));
          cs.restoreGraphicsState();
          next++;
        }
      }
    }
  }

  private static int entryOf(PrintJob job, int labelIndex) {
    int seen = 0;
    for (int i = 0; i < job.entries().size(); i++) {
      seen += job.entries().get(i).copies();
      if (labelIndex < seen) return i;
    }
    throw new IllegalArgumentException("label index out of range");
  }

  private PDAppearanceStream form(PDDocument doc, LabelTemplate t, List<LabelElement> elements, PDFont regular, PDFont bold,
                                  Map<String, PDImageXObject> pictures) {
    try {
      PDAppearanceStream form = new PDAppearanceStream(doc);
      double w = t.widthMm() * PT_PER_MM;
      double h = t.heightMm() * PT_PER_MM;
      form.setBBox(new PDRectangle((float) w, (float) h));
      form.setResources(new PDResources());
      try (PDPageContentStream cs = new PDPageContentStream(doc, form)) {
        for (LabelElement e : elements) {
          switch (e) {
            case LabelElement.Text x -> text(doc, cs, x, h, x.bold() ? bold : regular, pictures);
            case LabelElement.Barcode b -> barcode(cs, b, h, regular);
          }
        }
      }
      return form;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void text(PDDocument doc, PDPageContentStream cs, LabelElement.Text t, double heightPt, PDFont font,
                    Map<String, PDImageXObject> pictures) throws IOException {
    if (t.gujarati()) {
      GujaratiRenderer.Bitmap bmp = gujarati.render(t.text(), t.fontPt(), t.bold(), PICTURE_DPI, t.widthMm());
      PDImageXObject image = pictures.get(t.text() + t.fontPt() + t.bold() + t.widthMm());
      if (image == null) {
        BufferedImage bi = new BufferedImage(bmp.width(), bmp.height(), BufferedImage.TYPE_BYTE_GRAY);
        bi.getRaster().setDataElements(0, 0, bmp.width(), bmp.height(), bmp.gray());
        image = LosslessFactory.createFromImage(doc, bi);
        pictures.put(t.text() + t.fontPt() + t.bold() + t.widthMm(), image);
      }
      double wPt = bmp.width() * 72.0 / PICTURE_DPI;
      double hPt = bmp.height() * 72.0 / PICTURE_DPI;
      double x = aligned(t.xMm() * PT_PER_MM, t.widthMm() * PT_PER_MM, wPt, t.align());
      cs.drawImage(image, (float) x, (float) (heightPt - t.yMm() * PT_PER_MM - hPt + 0.1 * t.fontPt()), (float) wPt, (float) hPt);
      return;
    }
    String clean = sanitize(t.text());
    double widthPt = font.getStringWidth(clean) / 1000 * t.fontPt();
    double x = aligned(t.xMm() * PT_PER_MM, t.widthMm() * PT_PER_MM, widthPt, t.align());
    cs.beginText();
    cs.setFont(font, (float) t.fontPt());
    cs.newLineAtOffset((float) x, (float) (heightPt - t.yMm() * PT_PER_MM - t.fontPt()));
    cs.showText(clean);
    cs.endText();
  }

  private static double aligned(double boxX, double boxW, double contentW, Alignment align) {
    return switch (align) {
      case LEFT -> boxX;
      case CENTER -> boxX + (boxW - contentW) / 2;
      case RIGHT -> boxX + boxW - contentW;
    };
  }

  /** Keeps letters the PDF font can show (basic Latin and Latin-1) and replaces the rest with a question mark. */
  static String sanitize(String text) {
    StringBuilder sb = new StringBuilder(text.length());
    text.codePoints().forEach(c -> sb.append(c >= 0x20 && c <= 0x7E || c >= 0xA0 && c <= 0xFF ? (char) c : '?'));
    return sb.toString();
  }

  private void barcode(PDPageContentStream cs, LabelElement.Barcode b, double heightPt, PDFont font) throws IOException {
    BitMatrix bits = BarcodeBits.encode(b.type(), b.payload());
    double x0 = b.xMm() * PT_PER_MM;
    double w = b.widthMm() * PT_PER_MM;
    double h = b.heightMm() * PT_PER_MM;
    double top = heightPt - b.yMm() * PT_PER_MM;
    cs.setNonStrokingColor(0f);
    if (bits.getHeight() == 1) {
      double digits = b.humanReadable() ? DIGIT_BOX_MM * PT_PER_MM : 0;
      double module = w / bits.getWidth();
      for (int x = 0; x < bits.getWidth(); x++) {
        if (!bits.get(x, 0)) continue;
        int end = x;
        while (end + 1 < bits.getWidth() && bits.get(end + 1, 0)) end++;
        cs.addRect((float) (x0 + x * module), (float) (top - h + digits), (float) ((end - x + 1) * module), (float) (h - digits));
        x = end;
      }
      cs.fill();
      if (b.humanReadable()) {
        String digitsText = sanitize(b.payload());
        double tw = font.getStringWidth(digitsText) / 1000 * DIGIT_PT;
        cs.beginText();
        cs.setFont(font, (float) DIGIT_PT);
        cs.newLineAtOffset((float) (x0 + (w - tw) / 2), (float) (top - h + 0.5));
        cs.showText(digitsText);
        cs.endText();
      }
    } else {
      double cell = Math.min(w, h) / bits.getWidth();
      for (int y = 0; y < bits.getHeight(); y++) {
        for (int x = 0; x < bits.getWidth(); x++) {
          if (!bits.get(x, y)) continue;
          int end = x;
          while (end + 1 < bits.getWidth() && bits.get(end + 1, y)) end++;
          cs.addRect((float) (x0 + x * cell), (float) (top - (y + 1) * cell), (float) ((end - x + 1) * cell), (float) cell);
          x = end;
        }
      }
      cs.fill();
    }
  }
}
