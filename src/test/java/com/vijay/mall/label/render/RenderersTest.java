package com.vijay.mall.label.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.label.LabelTestData;
import com.vijay.mall.label.domain.LabelData;
import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.domain.PrintJob;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class RenderersTest {
  private final GujaratiRenderer gujarati = new AwtGujaratiRenderer();
  private final LabelTemplate thermal = LabelTestData.thermal();
  private final PrintJob plain = LabelTestData.job(thermal, 1, LabelTestData.entry(thermal, LabelTestData.data(""), 3));

  private static LabelTemplate template(BarcodeType type, NameLanguage lang, Alignment align) {
    return new LabelTemplate(UUID.randomUUID(), "t", Kind.THERMAL, 60, 70, 1, 1, 0, 0, 0, 0, type,
        new LabelFields(true, lang, true, true, true, true, true, true, true, 10, align), false);
  }

  private static LabelData full(String gu, String payload) {
    return new LabelData("Vijay Mall", "Rice \"Gold\" ^x~", gu, "1 kg", 6500, 5900, "Rs 59.00/kg", LabelTestData.day(9), LabelTestData.day(20), "B7", payload);
  }

  // ---- ZPL ----
  @Test void zpl_knownLabel() {
    String zpl = new String(new ZplRenderer(gujarati).render(plain), StandardCharsets.US_ASCII);
    assertThat(zpl).isEqualTo("""
        ^XA
        ^CI28
        ^PW400
        ^LL200
        ^LH0,0
        ^FO12,12^A0N,20,20^FB376,1,0,C,0^FDBasmati Rice^FS
        ^FO12,36^A0N,20,20^FB376,1,0,C,0^FD1 kg^FS
        ^FO12,59^A0N,20,20^FB376,1,0,C,0^FDMRP Rs 65.00^FS
        ^FO12,83^A0N,25,25^FB376,1,0,C,0^FDRs 59.00^FS
        ^FO57,117^BY3,3,49^BEN,49,Y,N^FD290000000001^FS
        ^PQ3,0,1,N
        ^XZ
        """);
  }

  @Test void zpl_code128AndQrAndAlignments() {
    var t128 = template(BarcodeType.CODE128, NameLanguage.EN, Alignment.LEFT);
    String z = new String(new ZplRenderer(gujarati).render(LabelTestData.job(t128, 1, LabelTestData.entry(t128, full("", "ABC-123"), 1))), StandardCharsets.US_ASCII);
    assertThat(z).contains("^BCN,").contains("^FDABC-123^FS").contains(",L,0^FD").contains("^FDRice ?Gold? ?x?^FS").contains("^FDPkd 09/11/26  BB 20/11/26^FS");
    var tqr = template(BarcodeType.QR, NameLanguage.EN, Alignment.RIGHT);
    String q = new String(new ZplRenderer(gujarati).render(LabelTestData.job(tqr, 1, LabelTestData.entry(tqr, full("", "ABC-123"), 2))), StandardCharsets.US_ASCII);
    assertThat(q).contains("^BQN,2,").contains("^FDQA,ABC-123^FS").contains(",R,0^FD").contains("^PQ2,0,1,N");
  }

  @Test void zpl_gujaratiIsAHexPicture() {
    var t = template(BarcodeType.EAN13, NameLanguage.BOTH, Alignment.CENTER);
    String z = new String(new ZplRenderer(gujarati).render(LabelTestData.job(t, 1, LabelTestData.entry(t, full("બાસમતી ચોખા", LabelTestData.EAN), 1))), StandardCharsets.US_ASCII);
    assertThat(z).containsPattern("\\^GFA,\\d+,\\d+,\\d+,[0-9A-F]+\\^FS");
  }

  // ---- TSPL ----
  @Test void tspl_knownLabel() {
    String tspl = new String(new TsplRenderer(gujarati).render(plain), StandardCharsets.ISO_8859_1);
    assertThat(tspl).isEqualTo("SIZE 50 mm,25 mm\r\nGAP 2 mm,0 mm\r\nDIRECTION 1,0\r\nREFERENCE 0,0\r\nCLS\r\n"
        + "TEXT 104,12,\"3\",0,1,1,\"Basmati Rice\"\r\n"
        + "TEXT 168,36,\"3\",0,1,1,\"1 kg\"\r\n"
        + "TEXT 104,59,\"3\",0,1,1,\"MRP Rs 65.00\"\r\n"
        + "TEXT 136,83,\"3\",0,1,1,\"Rs 59.00\"\r\n"
        + "BARCODE 57,117,\"EAN13\",49,1,0,3,6,\"2900000000018\"\r\n"
        + "PRINT 3,1\r\n");
  }

  @Test void tspl_fractionalSizeCode128QrAndLongTextCut() {
    var t = new LabelTemplate(UUID.randomUUID(), "t", Kind.THERMAL, 38.5, 70, 1, 1, 0, 0, 0, 0, BarcodeType.CODE128,
        new LabelFields(true, NameLanguage.EN, true, true, true, true, true, true, true, 6, Alignment.LEFT), false);
    String s = new String(new TsplRenderer(gujarati).render(LabelTestData.job(t, 1, LabelTestData.entry(t, full("", "ABC-123"), 1))), StandardCharsets.ISO_8859_1);
    assertThat(s).startsWith("SIZE 38.5 mm,70 mm").contains("\"128\"").contains("\"ABC-123\"");
    var tq = template(BarcodeType.QR, NameLanguage.EN, Alignment.RIGHT);
    String q = new String(new TsplRenderer(gujarati).render(LabelTestData.job(tq, 1, LabelTestData.entry(tq, full("", "ABC-123"), 1))), StandardCharsets.ISO_8859_1);
    assertThat(q).contains("QRCODE ").contains(",M,").contains("\"ABC-123\"");
  }

  @Test void tspl_gujaratiIsABitmapWithRawBytes() {
    var t = template(BarcodeType.EAN13, NameLanguage.BOTH, Alignment.CENTER);
    byte[] out = new TsplRenderer(gujarati).render(LabelTestData.job(t, 1, LabelTestData.entry(t, full("બાસમતી ચોખા", LabelTestData.EAN), 1)));
    String s = new String(out, StandardCharsets.ISO_8859_1);
    assertThat(s).containsPattern("BITMAP \\d+,\\d+,\\d+,\\d+,0,");
    assertThat(s).endsWith("PRINT 1,1\r\n");
  }

  @Test void tspl_oneDesignAfterAnotherEachWithItsOwnCopies() {
    var a = LabelTestData.entry(thermal, LabelTestData.data(""), 2);
    var b = LabelTestData.entry(thermal, LabelTestData.data(""), 5);
    String s = new String(new TsplRenderer(gujarati).render(LabelTestData.job(thermal, 1, a, b)), StandardCharsets.ISO_8859_1);
    assertThat(s).contains("PRINT 2,1").contains("PRINT 5,1");
    assertThat(s.split("CLS", -1)).hasSize(3);
  }

  // ---- ESC/POS ----
  @Test void escpos_nativeEanBarcodeTextAndCut() {
    byte[] out = new EscPosRenderer(gujarati).render(plain);
    String s = new String(out, StandardCharsets.ISO_8859_1);
    assertThat(s).contains("Basmati Rice").contains("MRP Rs 65.00").contains("Rs 59.00");
    byte[] ean = {0x1D, 0x6B, 67, 13};
    assertThat(indexOf(out, ean)).isPositive();
    assertThat(s).contains("2900000000018");
    assertThat(count(out, new byte[] {0x1D, 0x56, 66, 0})).isEqualTo(3);
    assertThat(out[0]).isEqualTo((byte) 0x1B);
  }

  @Test void escpos_code128_qr_gujarati() {
    var t128 = template(BarcodeType.CODE128, NameLanguage.EN, Alignment.RIGHT);
    byte[] c = new EscPosRenderer(gujarati).render(LabelTestData.job(t128, 1, LabelTestData.entry(t128, full("", "ABC-123"), 1)));
    assertThat(indexOf(c, new byte[] {0x1D, 0x6B, 73, 9, '{', 'B', 'A'})).isPositive();
    var tq = template(BarcodeType.QR, NameLanguage.EN, Alignment.LEFT);
    byte[] q = new EscPosRenderer(gujarati).render(LabelTestData.job(tq, 1, LabelTestData.entry(tq, full("", "ABC-123"), 1)));
    assertThat(indexOf(q, new byte[] {0x1D, 0x28, 0x6B, 10, 0, 0x31, 0x50, 0x30, 'A', 'B', 'C'})).isPositive();
    assertThat(indexOf(q, new byte[] {0x1D, 0x28, 0x6B, 3, 0, 0x31, 0x51, 0x30})).isPositive();
    var tg = template(BarcodeType.EAN13, NameLanguage.BOTH, Alignment.CENTER);
    byte[] g = new EscPosRenderer(gujarati).render(LabelTestData.job(tg, 1, LabelTestData.entry(tg, full("બાસમતી", LabelTestData.EAN), 2)));
    assertThat(indexOf(g, new byte[] {0x1D, 0x76, 0x30, 0})).isPositive();
    assertThat(count(g, new byte[] {0x1D, 0x56, 66, 0})).isEqualTo(2);
  }

  // ---- PDF ----
  private static int pages(byte[] pdf) throws IOException {
    try (PDDocument d = Loader.loadPDF(pdf)) {
      return d.getNumberOfPages();
    }
  }

  @Test void pdf_thermalIsOnePagePerLabelAtTheLabelSize() throws IOException {
    byte[] pdf = new PdfLabelRenderer(gujarati).render(plain);
    assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    try (PDDocument d = Loader.loadPDF(pdf)) {
      assertThat(d.getNumberOfPages()).isEqualTo(3);
      assertThat(d.getPage(0).getMediaBox().getWidth()).isEqualTo((float) (50 * 72 / 25.4), org.assertj.core.data.Offset.offset(0.01f));
      assertThat(d.getPage(0).getMediaBox().getHeight()).isEqualTo((float) (25 * 72 / 25.4), org.assertj.core.data.Offset.offset(0.01f));
      String text = new PDFTextStripper().getText(d);
      assertThat(text).contains("Basmati Rice").contains("MRP Rs 65.00").contains("Rs 59.00").contains("2900000000018");
    }
  }

  @Test void pdf_sheetFillsA4PagesFromTheStartPosition() throws IOException {
    var sheet = LabelTestData.sheet();
    var e = LabelTestData.entry(sheet, LabelTestData.data(""), 29);
    byte[] pdf = new PdfLabelRenderer(gujarati).render(LabelTestData.job(sheet, 20, e));
    try (PDDocument d = Loader.loadPDF(pdf)) {
      assertThat(d.getNumberOfPages()).isEqualTo(2);
      assertThat(d.getPage(0).getMediaBox().getWidth()).isEqualTo((float) (210 * 72 / 25.4), org.assertj.core.data.Offset.offset(0.01f));
      var stripper = new PDFTextStripper();
      stripper.setStartPage(1);
      stripper.setEndPage(1);
      assertThat(count(stripper.getText(d), "Basmati Rice")).isEqualTo(5);
      stripper.setStartPage(2);
      stripper.setEndPage(2);
      assertThat(count(stripper.getText(d), "Basmati Rice")).isEqualTo(24);
    }
  }

  @Test void pdf_severalVariantsInOrderAndTwoPagesOnASheet() throws IOException {
    var sheet = LabelTestData.sheet();
    var a = LabelTestData.entry(sheet, LabelTestData.data(""), 10);
    var other = new LabelData("Vijay", "Sugar", "", "1 kg", 5000, 4500, null, null, null, null, "2900000000025");
    var b = LabelTestData.entry(sheet, other, 20);
    try (PDDocument d = Loader.loadPDF(new PdfLabelRenderer(gujarati).render(LabelTestData.job(sheet, 1, a, b)))) {
      assertThat(d.getNumberOfPages()).isEqualTo(2);
      String all = new PDFTextStripper().getText(d);
      assertThat(count(all, "Basmati Rice")).isEqualTo(10);
      assertThat(count(all, "Sugar")).isEqualTo(20);
    }
  }

  @Test void pdf_gujaratiCode128QrAndOddCharacters() throws IOException {
    var tg = template(BarcodeType.EAN13, NameLanguage.BOTH, Alignment.RIGHT);
    var d1 = LabelTestData.entry(tg, new LabelData("Vijay Café", "Rice ₹ ✓", "બાસમતી ચોખા", "1 kg", 6500, 5900, null, null, null, null, LabelTestData.EAN), 2);
    assertThat(pages(new PdfLabelRenderer(gujarati).render(LabelTestData.job(tg, 1, d1)))).isEqualTo(2);
    var t128 = template(BarcodeType.CODE128, NameLanguage.EN, Alignment.LEFT);
    assertThat(pages(new PdfLabelRenderer(gujarati).render(LabelTestData.job(t128, 1, LabelTestData.entry(t128, full("", "ABC-123"), 1))))).isEqualTo(1);
    var tq = new LabelTemplate(UUID.randomUUID(), "q", Kind.SHEET, 40, 40, 4, 6, 3, 3, 10, 10, BarcodeType.QR, new LabelFields(false, NameLanguage.EN, false, false, true, false, false, false, false, 8, Alignment.CENTER), false);
    assertThat(pages(new PdfLabelRenderer(gujarati).render(LabelTestData.job(tq, 2, LabelTestData.entry(tq, full("", "ABC-123"), 3))))).isEqualTo(1);
  }

  @Test void pdf_theSameGujaratiPictureIsReused() throws IOException {
    var tg = template(BarcodeType.EAN13, NameLanguage.BOTH, Alignment.CENTER);
    var d1 = LabelTestData.entry(tg, full("બાસમતી ચોખા", LabelTestData.EAN), 1);
    var d2 = LabelTestData.entry(tg, full("બાસમતી ચોખા", LabelTestData.EAN), 1);
    assertThat(pages(new PdfLabelRenderer(gujarati).render(LabelTestData.job(tg, 1, d1, d2)))).isEqualTo(2);
  }

  @Test void pdf_aPrintRequestFor500LabelsIsFast() throws IOException {
    var sheet = LabelTestData.sheet();
    var t = new PdfLabelRenderer(gujarati);
    var job = LabelTestData.job(sheet, 1, LabelTestData.entry(sheet, LabelTestData.data(""), 500));
    t.render(job);
    long start = System.nanoTime();
    byte[] pdf = t.render(job);
    long ms = (System.nanoTime() - start) / 1_000_000;
    System.out.println("PDF 500 labels sheet: " + ms + " ms, " + pdf.length + " bytes");
    assertThat(pages(pdf)).isEqualTo(21);
    assertThat(ms).isLessThan(3000);
  }

  @Test void barcodeBits_ean13Is95ModulesWide_andBadPayloadsAreRejected() {
    assertThat(BarcodeBits.encode(BarcodeType.EAN13, LabelTestData.EAN).getWidth()).isEqualTo(95);
    assertThat(BarcodeBits.encode(BarcodeType.QR, "hello").getHeight()).isGreaterThan(15);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> BarcodeBits.encode(BarcodeType.EAN13, "12")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test void printerSupport_asciiAndAlignment() {
    assertThat(PrinterSupport.ascii("a\"b^c~d\\e é\n")).isEqualTo("a?b?c?d?e ??");
    assertThat(PrinterSupport.alignedX(10, 100, 40, Alignment.LEFT)).isEqualTo(10);
    assertThat(PrinterSupport.alignedX(10, 100, 40, Alignment.CENTER)).isEqualTo(40);
    assertThat(PrinterSupport.alignedX(10, 100, 40, Alignment.RIGHT)).isEqualTo(70);
    assertThat(PrinterSupport.alignedX(10, 30, 40, Alignment.RIGHT)).isEqualTo(10);
  }

  private static int indexOf(byte[] data, byte[] pattern) {
    outer:
    for (int i = 0; i <= data.length - pattern.length; i++) {
      for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
      return i;
    }
    return -1;
  }

  private static int count(byte[] data, byte[] pattern) {
    int n = 0;
    for (int i = 0; i <= data.length - pattern.length; i++) {
      boolean same = true;
      for (int j = 0; j < pattern.length && same; j++) same = data[i + j] == pattern[j];
      if (same) n++;
    }
    return n;
  }

  private static int count(String text, String needle) {
    List<Integer> hits = new ArrayList<>();
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) hits.add(i);
    return hits.size();
  }
}
