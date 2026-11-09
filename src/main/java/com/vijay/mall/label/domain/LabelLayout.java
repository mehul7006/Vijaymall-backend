package com.vijay.mall.label.domain;

import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Works out where everything goes on one label: text lines from the top, the barcode in the space that is left. Every output
 * format (PDF, printer languages, JSON) draws from these positions, so they all agree.
 */
public final class LabelLayout {
  /** Space kept free at every edge of a label, in millimetres. */
  public static final double PADDING_MM = 1.5;
  /** A point in millimetres. */
  static final double MM_PER_PT = 25.4 / 72;
  /** Line height as a multiple of the font size. */
  static final double LINE_FACTOR = 1.2;
  /** Smallest barcode box height for EAN-13 and Code 128 (bars plus digits). */
  static final double MIN_LINEAR_MM = 7;
  /** Largest barcode box height for EAN-13 and Code 128. */
  static final double MAX_LINEAR_MM = 16;
  /** Smallest QR code side. */
  static final double MIN_QR_MM = 10;
  /** Widest EAN-13 drawn (the nominal size is 37.3 mm). */
  static final double EAN_WIDTH_MM = 36;
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yy", Locale.ROOT);

  private LabelLayout() { }

  /**
   * The result of laying out a label.
   *
   * @param elements the things to draw
   * @param neededMm height the content needs (padding included)
   * @param availableMm height the label has
   */
  public record Layout(List<LabelElement> elements, double neededMm, double availableMm) {
    /** True when everything fits inside the label. */
    public boolean fits() { return neededMm <= availableMm + 1e-9; }
  }

  /** Formats paise as {@code Rs 59.00}; the rupee sign is not in printer fonts. */
  public static String money(long paise) { return String.format(Locale.ROOT, "Rs %d.%02d", paise / 100, paise % 100); }

  /** True when the text has a letter from the Gujarati block. */
  public static boolean hasGujarati(String text) { return text.chars().anyMatch(c -> c >= 0x0A80 && c <= 0x0AFF); }

  /** Lays out one label of the template with the given data. */
  public static Layout layout(LabelTemplate t, LabelData d) {
    LabelFields f = t.fields();
    double pad = PADDING_MM;
    double usableW = t.widthMm() - 2 * pad;
    double base = f.fontSize();
    List<LabelElement> out = new ArrayList<>();
    double y = pad;
    if (f.shopName() && notBlank(d.shopName())) y = line(out, y, usableW, d.shopName(), base, true, f.alignment());
    NameLanguage lang = f.productName();
    if (lang == NameLanguage.EN || lang == NameLanguage.BOTH || (lang == NameLanguage.GU && blank(d.nameGu()))) {
      y = line(out, y, usableW, d.nameEn(), base, true, f.alignment());
    }
    if ((lang == NameLanguage.GU || lang == NameLanguage.BOTH) && notBlank(d.nameGu())) {
      y = line(out, y, usableW, d.nameGu(), base, false, f.alignment());
    }
    String pack = packLine(f, d);
    if (!pack.isEmpty()) y = line(out, y, usableW, pack, base, false, f.alignment());
    if (f.mrp()) y = line(out, y, usableW, "MRP " + money(d.mrpPaise()), base, false, f.alignment());
    if (f.price()) y = line(out, y, usableW, money(d.pricePaise()), base + 2, true, f.alignment());
    String dates = dateLine(f, d);
    if (!dates.isEmpty()) y = line(out, y, usableW, dates, base, false, f.alignment());
    if (f.batchNo() && notBlank(d.batchNo())) y = line(out, y, usableW, "Batch " + d.batchNo().strip(), base, false, f.alignment());
    if (!out.isEmpty()) y += 0.5;
    double room = t.heightMm() - pad - y;
    boolean qr = t.barcodeType() == BarcodeType.QR;
    double minimum = qr ? MIN_QR_MM : MIN_LINEAR_MM;
    double height = qr ? Math.min(Math.min(room, usableW), 24) : Math.min(room, MAX_LINEAR_MM);
    double width = qr ? height : t.barcodeType() == BarcodeType.EAN13 ? Math.min(usableW, EAN_WIDTH_MM) : usableW;
    if (room >= minimum) {
      out.add(new LabelElement.Barcode(pad + (usableW - width) / 2, y, width, height, t.barcodeType(), d.barcodePayload(), !qr));
    }
    return new Layout(out, y + minimum + pad, t.heightMm());
  }

  private static String packLine(LabelFields f, LabelData d) {
    String pack = f.packSize() && notBlank(d.packSize()) ? d.packSize().strip() : "";
    String per = f.pricePerUnit() && notBlank(d.pricePerUnit()) ? d.pricePerUnit() : "";
    if (!pack.isEmpty() && !per.isEmpty()) return pack + "  " + per;
    return pack.isEmpty() ? per : pack;
  }

  private static String dateLine(LabelFields f, LabelData d) {
    String packed = f.packedOn() && d.packedOn() != null ? "Pkd " + date(d.packedOn()) : "";
    String best = f.bestBefore() && d.bestBefore() != null ? "BB " + date(d.bestBefore()) : "";
    return packed.isEmpty() ? best : best.isEmpty() ? packed : packed + "  " + best;
  }

  private static String date(LocalDate date) { return DATE.format(date); }

  private static double line(List<LabelElement> out, double y, double usableW, String text, double pt, boolean bold, Alignment align) {
    double h = pt * MM_PER_PT * LINE_FACTOR;
    boolean gu = hasGujarati(text);
    out.add(new LabelElement.Text(PADDING_MM, y, usableW, h, fit(text, pt, usableW, gu), pt, bold, align, gu));
    return y + h;
  }

  /** Cuts a text that is too wide for the line, using an average character width, and ends it with two dots. */
  static String fit(String text, double pt, double widthMm, boolean gujarati) {
    String clean = text.strip();
    double charMm = pt * MM_PER_PT * (gujarati ? 0.65 : 0.55);
    int max = Math.max(3, (int) (widthMm / charMm));
    return clean.length() <= max ? clean : clean.substring(0, max - 2) + "..";
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }

  private static boolean notBlank(String s) { return !blank(s); }
}
