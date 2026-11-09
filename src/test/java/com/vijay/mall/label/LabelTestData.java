package com.vijay.mall.label;

import com.vijay.mall.label.domain.LabelData;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelLayout;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.domain.PrintJob;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Builders shared by the label tests. */
public final class LabelTestData {
  public static final UUID TEMPLATE_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
  public static final String EAN = "2900000000018";

  private LabelTestData() { }

  public static LabelFields fields(NameLanguage lang) {
    return new LabelFields(false, lang, true, true, true, false, false, false, false, 7, Alignment.CENTER);
  }

  /** The built-in 50 x 25 mm thermal template. */
  public static LabelTemplate thermal() {
    return new LabelTemplate(TEMPLATE_ID, "50 x 25 mm thermal", Kind.THERMAL, 50, 25, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, fields(NameLanguage.EN), true);
  }

  /** The built-in A4 3 x 8 sheet. */
  public static LabelTemplate sheet() {
    return new LabelTemplate(UUID.randomUUID(), "A4 sheet 3 x 8", Kind.SHEET, 64, 34, 3, 8, 2, 2, 7, 5.5, BarcodeType.EAN13,
        new LabelFields(true, NameLanguage.EN, true, true, true, false, false, false, false, 8, Alignment.CENTER), true);
  }

  public static LabelData data(String nameGu) {
    return new LabelData("Vijay Super Mall", "Basmati Rice", nameGu, "1 kg", 6500, 5900, null, null, null, null, EAN);
  }

  public static PrintJob.Entry entry(LabelTemplate t, LabelData d, int copies) {
    List<LabelElement> elements = LabelLayout.layout(t, d).elements();
    return new PrintJob.Entry(UUID.randomUUID(), d.nameEn(), copies, elements, t.barcodeType(), d.barcodePayload());
  }

  public static PrintJob job(LabelTemplate t, int start, PrintJob.Entry... entries) { return new PrintJob(t, 203, start, List.of(entries)); }

  public static LocalDate day(int d) { return LocalDate.of(2026, 11, d); }
}
