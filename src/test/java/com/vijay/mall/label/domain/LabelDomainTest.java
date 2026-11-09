package com.vijay.mall.label.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.label.LabelTestData;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LabelDomainTest {
  private final LabelTemplate thermal = LabelTestData.thermal();

  private static List<String> texts(LabelLayout.Layout l) {
    return l.elements().stream().filter(e -> e instanceof LabelElement.Text).map(e -> ((LabelElement.Text) e).text()).toList();
  }

  @Test void money_isRupeesAndPaise() {
    assertThat(LabelLayout.money(5900)).isEqualTo("Rs 59.00");
    assertThat(LabelLayout.money(105)).isEqualTo("Rs 1.05");
  }

  @Test void hasGujarati_detectsTheGujaratiBlockOnly() {
    assertThat(LabelLayout.hasGujarati("ચોખા")).isTrue();
    assertThat(LabelLayout.hasGujarati("Rice")).isFalse();
  }

  @Test void layout_putsTextTopDownAndTheBarcodeInTheRemainingSpace() {
    var layout = LabelLayout.layout(thermal, LabelTestData.data(""));
    assertThat(texts(layout)).containsExactly("Basmati Rice", "1 kg", "MRP Rs 65.00", "Rs 59.00");
    var barcode = (LabelElement.Barcode) layout.elements().getLast();
    assertThat(barcode.type()).isEqualTo(BarcodeType.EAN13);
    assertThat(barcode.payload()).isEqualTo(LabelTestData.EAN);
    assertThat(barcode.yMm()).isGreaterThan(10).isLessThan(15);
    assertThat(barcode.yMm() + barcode.heightMm()).isLessThanOrEqualTo(25 - LabelLayout.PADDING_MM + 1e-9);
    assertThat(barcode.xMm() + barcode.widthMm() / 2).isEqualTo(25, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(layout.fits()).isTrue();
    var ys = layout.elements().stream().filter(e -> e instanceof LabelElement.Text).map(e -> ((LabelElement.Text) e).yMm()).toList();
    assertThat(ys).isSorted();
  }

  @Test void layout_priceLineIsBiggerAndBold() {
    var layout = LabelLayout.layout(thermal, LabelTestData.data(""));
    var price = (LabelElement.Text) layout.elements().get(3);
    assertThat(price.fontPt()).isEqualTo(9);
    assertThat(price.bold()).isTrue();
    assertThat(((LabelElement.Text) layout.elements().get(1)).bold()).isFalse();
  }

  @Test void layout_bothLanguagesGiveTwoNameLines_andGujaratiFallsBackToEnglishWhenMissing() {
    var both = thermal.withIdentity(thermal.id(), "x", false);
    var f = new LabelFields(false, NameLanguage.BOTH, false, false, true, false, false, false, false, 7, Alignment.LEFT);
    var t = new LabelTemplate(both.id(), "x", both.kind(), 60, 40, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false);
    var withGu = LabelLayout.layout(t, LabelTestData.data("ચોખા"));
    assertThat(texts(withGu)).containsExactly("Basmati Rice", "ચોખા", "Rs 59.00");
    assertThat(((LabelElement.Text) withGu.elements().get(1)).gujarati()).isTrue();
    assertThat(texts(LabelLayout.layout(t, LabelTestData.data("")))).containsExactly("Basmati Rice", "Rs 59.00");
    var guOnly = new LabelFields(false, NameLanguage.GU, false, false, false, false, false, false, false, 7, Alignment.LEFT);
    var tg = new LabelTemplate(both.id(), "x", both.kind(), 60, 40, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, guOnly, false);
    assertThat(texts(LabelLayout.layout(tg, LabelTestData.data("ચોખા")))).containsExactly("ચોખા");
    assertThat(texts(LabelLayout.layout(tg, LabelTestData.data(" ")))).containsExactly("Basmati Rice");
  }

  @Test void layout_datesBatchShopAndPricePerUnit() {
    var f = new LabelFields(true, NameLanguage.NONE, true, false, false, true, true, true, true, 7, Alignment.RIGHT);
    var t = new LabelTemplate(thermal.id(), "x", Kind(), 70, 50, 1, 1, 0, 0, 0, 0, BarcodeType.CODE128, f, false);
    var d = new LabelData("Vijay", "Rice", "", "1 kg", 6500, 5900, "Rs 59.00/kg", LabelTestData.day(9), LabelTestData.day(20), " B7 ", "ABC-123");
    var layout = LabelLayout.layout(t, d);
    assertThat(texts(layout)).containsExactly("Vijay", "1 kg  Rs 59.00/kg", "Pkd 09/11/26  BB 20/11/26", "Batch B7");
    var barcode = (LabelElement.Barcode) layout.elements().getLast();
    assertThat(barcode.type()).isEqualTo(BarcodeType.CODE128);
    assertThat(barcode.widthMm()).isEqualTo(70 - 2 * LabelLayout.PADDING_MM, org.assertj.core.data.Offset.offset(1e-9));
    assertThat(((LabelElement.Text) layout.elements().getFirst()).align()).isEqualTo(Alignment.RIGHT);
  }

  @Test void layout_singleDateAndPerUnitWithoutPack() {
    var f = new LabelFields(false, NameLanguage.NONE, false, false, false, true, true, false, true, 7, Alignment.LEFT);
    var t = new LabelTemplate(thermal.id(), "x", Kind(), 70, 50, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false);
    var d = new LabelData("", "Rice", "", "", 0, 0, "Rs 59.00/kg", LabelTestData.day(9), null, "", LabelTestData.EAN);
    assertThat(texts(LabelLayout.layout(t, d))).containsExactly("Rs 59.00/kg", "Pkd 09/11/26");
    var d2 = new LabelData("", "Rice", "", "", 0, 0, null, null, LabelTestData.day(9), "", LabelTestData.EAN);
    assertThat(texts(LabelLayout.layout(t, d2))).containsExactly("BB 09/11/26");
  }

  private static LabelTemplate.Kind Kind() { return LabelTemplate.Kind.THERMAL; }

  @Test void layout_qrIsSquareAndCentred() {
    var t = new LabelTemplate(thermal.id(), "x", Kind(), 40, 40, 1, 1, 0, 0, 0, 0, BarcodeType.QR, LabelTestData.fields(NameLanguage.EN), false);
    var qr = (LabelElement.Barcode) LabelLayout.layout(t, LabelTestData.data("")).elements().getLast();
    assertThat(qr.widthMm()).isEqualTo(qr.heightMm());
    assertThat(qr.humanReadable()).isFalse();
  }

  @Test void layout_reportsWhenItDoesNotFit() {
    var f = new LabelFields(true, NameLanguage.BOTH, true, true, true, true, true, true, true, 12, Alignment.CENTER);
    var t = new LabelTemplate(thermal.id(), "x", Kind(), 30, 20, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false);
    var layout = LabelLayout.layout(t, new LabelData("Shop", "Rice", "ચોખા", "1 kg", 100, 90, "Rs 1.00/kg", LabelTestData.day(1), LabelTestData.day(2), "B", LabelTestData.EAN));
    assertThat(layout.fits()).isFalse();
    assertThat(layout.elements()).noneMatch(e -> e instanceof LabelElement.Barcode);
    assertThat(layout.neededMm()).isGreaterThan(layout.availableMm());
  }

  @Test void fit_cutsLongNames() {
    assertThat(LabelLayout.fit("A very long product name that cannot fit", 7, 20, false)).endsWith("..").hasSizeLessThan(25);
    assertThat(LabelLayout.fit("Rice", 7, 20, false)).isEqualTo("Rice");
    assertThat(LabelLayout.fit("ચોખા ચોખા ચોખા ચોખા ચોખા ચોખા", 7, 10, true)).endsWith("..");
  }

  @Test void packSize_pricesPerKgLitreAndPiece() {
    assertThat(PackSize.pricePerUnit("500 g", 5900)).contains("Rs 118.00/kg");
    assertThat(PackSize.pricePerUnit("1kg", 5900)).contains("Rs 59.00/kg");
    assertThat(PackSize.pricePerUnit("250 ml", 5000)).contains("Rs 200.00/L");
    assertThat(PackSize.pricePerUnit("1,5 L", 15000)).contains("Rs 100.00/L");
    assertThat(PackSize.pricePerUnit("6 pcs", 3000)).contains("Rs 5.00/pc");
    assertThat(PackSize.pricePerUnit("2 Kgs.", 3000)).contains("Rs 15.00/kg");
  }

  @Test void packSize_omitsWhatItCannotRead() {
    assertThat(PackSize.pricePerUnit("Family pack", 100)).isEqualTo(Optional.empty());
    assertThat(PackSize.pricePerUnit("0 g", 100)).isEmpty();
    assertThat(PackSize.pricePerUnit("", 100)).isEmpty();
    assertThat(PackSize.pricePerUnit(null, 100)).isEmpty();
  }

  @Test void sheetPlacement_firstLabelsGoTopLeftRowByRow() {
    var t = LabelTestData.sheet();
    var places = SheetLayout.place(t, 1, 5);
    assertThat(places.get(0)).isEqualTo(new SheetLayout.Placement(1, 0, 0, 7, 5.5));
    assertThat(places.get(1).xMm()).isEqualTo(7 + 66);
    assertThat(places.get(2).xMm()).isEqualTo(7 + 132);
    assertThat(places.get(3)).isEqualTo(new SheetLayout.Placement(1, 0, 1, 7, 5.5 + 36));
  }

  @Test void sheetPlacement_startPositionSkipsUsedCellsAndRollsToNextPage() {
    var t = LabelTestData.sheet();
    var places = SheetLayout.place(t, 23, 4);
    assertThat(places).extracting(SheetLayout.Placement::page).containsExactly(1, 1, 2, 2);
    assertThat(places.get(0)).isEqualTo(new SheetLayout.Placement(1, 1, 7, 7 + 66, 5.5 + 7 * 36));
    assertThat(places.get(1).column()).isEqualTo(2);
    assertThat(places.get(2)).isEqualTo(new SheetLayout.Placement(2, 0, 0, 7, 5.5));
    assertThat(SheetLayout.pages(t, 23, 4)).isEqualTo(2);
    assertThat(SheetLayout.pages(t, 1, 24)).isEqualTo(1);
    assertThat(SheetLayout.pages(t, 1, 25)).isEqualTo(2);
    assertThat(SheetLayout.pages(t, 24, 1)).isEqualTo(1);
    assertThat(SheetLayout.pages(t, 24, 2)).isEqualTo(2);
  }

  @Test void template_perPageAndFieldDefaults() {
    assertThat(LabelTestData.sheet().perPage()).isEqualTo(24);
    assertThat(thermal.perPage()).isEqualTo(1);
    var f = new LabelFields(false, null, false, false, false, false, false, false, false, 7, null);
    assertThat(f.productName()).isEqualTo(NameLanguage.NONE);
    assertThat(f.alignment()).isEqualTo(Alignment.CENTER);
  }
}
