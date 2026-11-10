package com.vijay.mall.label.app;

import com.vijay.mall.common.tax.GstRates;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.label.LabelTestData;
import com.vijay.mall.label.app.LabelService.Item;
import com.vijay.mall.label.app.LabelService.Mode;
import com.vijay.mall.label.app.LabelService.Request;
import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelFields.Alignment;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.infra.LabelHistoryRepository;
import com.vijay.mall.label.render.AwtGujaratiRenderer;
import com.vijay.mall.label.render.EscPosRenderer;
import com.vijay.mall.label.render.GujaratiRenderer;
import com.vijay.mall.label.render.PdfLabelRenderer;
import com.vijay.mall.label.render.TsplRenderer;
import com.vijay.mall.label.render.ZplRenderer;
import com.vijay.mall.pos.app.BarcodeGenerationService;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.WeighedMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LabelServiceTest {
  private final UUID rice = UUID.randomUUID();
  private final UUID sugar = UUID.randomUUID();
  private final UUID actor = UUID.randomUUID();
  private LabelTemplateService templates;
  private CatalogService catalog;
  private PosCatalogService posCatalog;
  private BarcodeGenerationService generation;
  private InventoryService inventory;
  private LabelHistoryRepository history;
  private LabelLotSource lots;
  private AuditService audit;
  private GujaratiRenderer gujarati;
  private LabelService service;
  private final RuntimeSettings settings = mock(RuntimeSettings.class);
  private final List<LabelHistoryRepository.Row> saved = new ArrayList<>();

  private static ProductData product(UUID id, String name, String nameGu, String pack, long mrp, long price) {
    return new ProductData(id, UUID.randomUUID(), name, nameGu, "", mrp, price, "pc", pack, null, true, false, null, null, true, true);
  }

  private LabelService build(GujaratiRenderer g) {
    return new LabelService(templates, catalog, posCatalog, generation, inventory, shopService(), lots, history, settings, g,
        new PdfLabelRenderer(g), new TsplRenderer(g), new ZplRenderer(g), new EscPosRenderer(g), audit, Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
  }

  private ShopService shopService() {
    var shop = mock(ShopService.class);
    when(shop.get()).thenReturn(new ShopSetting("Vijay Super Mall", "", "", "", "24", "", "VSM", "", "2", WeighedMode.WEIGHT_GRAMS));
    return shop;
  }

  @BeforeEach
  void setUp() {
    when(settings.getInt(Settings.LABEL_DPI)).thenReturn(203);
    templates = mock(LabelTemplateService.class);
    catalog = mock(CatalogService.class);
    posCatalog = mock(PosCatalogService.class);
    generation = mock(BarcodeGenerationService.class);
    inventory = mock(InventoryService.class);
    history = mock(LabelHistoryRepository.class);
    lots = mock(LabelLotSource.class);
    audit = mock(AuditService.class);
    gujarati = new AwtGujaratiRenderer();
    var riceData = product(rice, "Basmati Rice", "બાસમતી ચોખા", "500 g", 6500, 5900);
    var sugarData = product(sugar, "Sugar", "", "1 kg", 5000, 4500);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(LabelTestData.thermal());
    when(catalog.posProducts(any())).thenAnswer(inv -> {
      Collection<UUID> ids = inv.getArgument(0);
      return List.of(riceData, sugarData).stream().filter(p -> ids.contains(p.id())).toList();
    });
    when(catalog.posVariantNames(any())).thenReturn(Map.of(rice, "Premium"));
    when(posCatalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.UNIT, null, GstRates.of(5), ""), sugar, new ProductPos(sugar, SoldBy.UNIT, null, GstRates.of(5), "")));
    when(posCatalog.barcodesOf(rice)).thenReturn(List.of("8901234567890"));
    when(posCatalog.barcodesOf(sugar)).thenReturn(List.of());
    when(generation.generate(eq(sugar), any())).thenReturn(new BarcodeGenerationService.Generated(sugar, "2900000000018", true, List.of("2900000000018")));
    when(lots.lotFor(any())).thenReturn(Optional.empty());
    org.mockito.Mockito.doAnswer(inv -> {
      saved.add(inv.getArgument(0));
      return null;
    }).when(history).insert(any());
    service = build(gujarati);
  }

  private Request req(Mode mode, Object copies, UUID... variants) {
    List<Item> items = new ArrayList<>();
    for (UUID v : variants) items.add(new Item(v, copies));
    return new Request(LabelTestData.TEMPLATE_ID, items, mode, null, null, null, null);
  }

  private void assertInvalid(Request r, String field) {
    assertThatThrownBy(() -> service.print(r, actor)).isInstanceOf(ApiException.class).satisfies(e -> {
      assertThat(((ApiException) e).status().value()).isEqualTo(400);
      assertThat(((ApiException) e).details().toString()).contains(field);
    });
  }

  @Test void pdf_printsOneVariantWithItsOwnBarcodeAndWritesHistoryAndAudit() {
    var out = service.print(req(Mode.PDF, 3, rice), actor);
    assertThat(out.mode()).isEqualTo(Mode.PDF);
    assertThat(new String(out.bytes(), 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
    assertThat(out.labels()).isEqualTo(3);
    assertThat(out.pages()).isEqualTo(3);
    assertThat(saved).hasSize(1);
    assertThat(saved.get(0)).satisfies(r -> {
      assertThat(r.actorId()).isEqualTo(actor);
      assertThat(r.copies()).isEqualTo(3);
      assertThat(r.mode()).isEqualTo("PDF");
      assertThat(r.templateName()).isEqualTo("50 x 25 mm thermal");
      assertThat(r.variantName()).isEqualTo("Basmati Rice Premium 500 g");
    });
    verify(audit).log(eq(actor), eq("PRINT"), eq("label"), eq(LabelTestData.TEMPLATE_ID), eq("PDF 3 labels for 1 variants"));
    verify(generation, never()).generate(any(), any());
  }

  @Test void ean13Template_aVariantWithoutABarcodeGetsAGeneratedOne() {
    var out = service.print(req(Mode.JSON, 2, sugar), actor);
    verify(generation).generate(sugar, actor);
    @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) out.json().get("items");
    @SuppressWarnings("unchecked") var barcode = (Map<String, Object>) items.get(0).get("barcode");
    assertThat(barcode).containsEntry("payload", "2900000000018").containsEntry("type", BarcodeType.EAN13);
  }

  @Test void ean13Template_aVariantWithOnlyOtherCodesGetsAGeneratedEan13_butCode128UsesItsOwnCode() {
    when(posCatalog.barcodesOf(rice)).thenReturn(List.of("ZZ-CODE", "8901234567891"));
    when(generation.generate(eq(rice), any())).thenReturn(new BarcodeGenerationService.Generated(rice, "2900000000025", true, List.of()));
    var json = service.print(req(Mode.JSON, 1, rice), actor).json();
    assertThat(json.toString()).contains("payload=2900000000025");
    var t128 = new LabelTemplate(LabelTestData.TEMPLATE_ID, "c", Kind.THERMAL, 50, 25, 1, 1, 0, 0, 0, 0, BarcodeType.CODE128, LabelTestData.fields(NameLanguage.EN), false);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(t128);
    assertThat(service.print(req(Mode.JSON, 1, rice), actor).json().toString()).contains("payload=ZZ-CODE");
  }

  @Test void ean13Template_aValidEan13IsPrintedAsIs() {
    when(posCatalog.barcodesOf(rice)).thenReturn(List.of("ZZ-CODE", "4006381333931"));
    assertThat(service.print(req(Mode.JSON, 1, rice), actor).json().toString()).contains("payload=4006381333931");
  }

  @Test void fromStock_oneLabelPerUnitCapped_andNoStockIsRefused() {
    when(inventory.stocks(any())).thenReturn(Map.of(rice, new InventoryRepository.Stock(rice, 12, 5), sugar, new InventoryRepository.Stock(sugar, 9000, 5)));
    assertThat(service.print(req(Mode.JSON, "fromStock", rice), actor).labels()).isEqualTo(12);
    assertThat(service.print(req(Mode.JSON, " FromStock ", rice), actor).labels()).isEqualTo(12);
    assertThat(service.print(req(Mode.JSON, "fromStock", sugar), actor).labels()).isEqualTo(500);
    when(inventory.stocks(any())).thenReturn(Map.of(rice, new InventoryRepository.Stock(rice, 0, 5)));
    assertInvalid(req(Mode.JSON, "fromStock", rice), "items[0].copies");
    when(inventory.stocks(any())).thenReturn(Map.of());
    assertInvalid(req(Mode.JSON, "fromStock", rice), "items[0].copies");
  }

  @Test void copies_mustBeAWholeNumberFrom1To500() {
    for (Object bad : new Object[] {0, 501, -1, 2.5, "many", true}) assertInvalid(req(Mode.PDF, bad, rice), "items[0].copies");
    assertInvalid(req(Mode.PDF, null, rice), "items[0].copies");
    assertThat(service.print(req(Mode.JSON, 500, rice), actor).labels()).isEqualTo(500);
    assertThat(service.print(req(Mode.JSON, 1, rice), actor).labels()).isEqualTo(1);
  }

  @Test void total_atMost2000LabelsPerCall() {
    var r = new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 500), new Item(sugar, 500), new Item(rice, 500), new Item(sugar, 500)), Mode.JSON, null, null, null, null);
    assertThat(service.print(r, actor).labels()).isEqualTo(2000);
    var over = new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 500), new Item(sugar, 500), new Item(rice, 500), new Item(sugar, 500), new Item(rice, 1)), Mode.JSON, null, null, null, null);
    assertInvalid(over, "2001");
  }

  @Test void items_mustBeThereKnownAndNotTooMany() {
    assertInvalid(new Request(LabelTestData.TEMPLATE_ID, List.of(), Mode.PDF, null, null, null, null), "items");
    assertInvalid(new Request(LabelTestData.TEMPLATE_ID, null, Mode.PDF, null, null, null, null), "items");
    assertInvalid(req(Mode.PDF, 1, UUID.randomUUID()), "items[0].variantId");
    List<Item> many = new ArrayList<>();
    for (int i = 0; i < 201; i++) many.add(new Item(rice, 1));
    assertInvalid(new Request(LabelTestData.TEMPLATE_ID, many, Mode.PDF, null, null, null, null), "items");
  }

  @Test void mode_isRequired_andSheetsOnlyPrintAsPdfOrJson() {
    assertInvalid(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), null, null, null, null, null), "mode");
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(LabelTestData.sheet());
    for (Mode m : new Mode[] {Mode.TSPL, Mode.ZPL, Mode.ESCPOS}) assertInvalid(req(m, 1, rice), "mode");
    assertThat(service.print(req(Mode.PDF, 1, rice), actor).pages()).isEqualTo(1);
  }

  @Test void startPosition_onlyForSheetsAndWithinTheSheet() {
    var thermalReq = new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), Mode.PDF, 2, null, null, null);
    assertInvalid(thermalReq, "startPosition");
    assertThat(service.print(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), Mode.PDF, 1, null, null, null), actor).labels()).isEqualTo(1);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(LabelTestData.sheet());
    for (int bad : new int[] {0, 25, -3}) {
      assertInvalid(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), Mode.PDF, bad, null, null, null), "startPosition");
    }
    var out = service.print(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 5)), Mode.JSON, 23, null, null, null), actor);
    assertThat(out.pages()).isEqualTo(2);
    @SuppressWarnings("unchecked") var sheet = (Map<String, Object>) out.json().get("sheet");
    assertThat(sheet).containsEntry("startPosition", 23).containsEntry("pages", 2).containsEntry("columns", 3);
    @SuppressWarnings("unchecked") var places = (List<Map<String, Object>>) sheet.get("placements");
    assertThat(places).hasSize(5);
    assertThat(places.get(0)).containsEntry("page", 1).containsEntry("column", 1).containsEntry("row", 7);
    assertThat(places.get(4)).containsEntry("page", 2).containsEntry("column", 2).containsEntry("row", 0);
  }

  @Test void dates_requestWinsOverLot_batchIsPrintedWhenTheTemplateHasIt() {
    var f = new LabelFields(false, NameLanguage.EN, false, false, true, true, true, true, false, 7, Alignment.CENTER);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(new LabelTemplate(LabelTestData.TEMPLATE_ID, "d", Kind.THERMAL, 60, 50, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false));
    when(lots.lotFor(rice)).thenReturn(Optional.of(new LabelLotSource.LotInfo(LabelTestData.day(1), LabelTestData.day(28), "LOT-9")));
    var fromLot = service.print(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), Mode.JSON, null, null, null, " "), actor).json().toString();
    assertThat(fromLot).contains("Pkd 01/11/26  BB 28/11/26").contains("Batch LOT-9");
    var fromRequest = service.print(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), Mode.JSON, null, LabelTestData.day(5), LabelTestData.day(6), " B-1 "), actor).json().toString();
    assertThat(fromRequest).contains("Pkd 05/11/26  BB 06/11/26").contains("Batch B-1");
  }

  @Test void bestBefore_mustNotBeBeforePackedOn() {
    assertInvalid(new Request(LabelTestData.TEMPLATE_ID, List.of(new Item(rice, 1)), Mode.PDF, null, LabelTestData.day(9), LabelTestData.day(8), null), "bestBefore");
  }

  @Test void weighedItems_areRefused() {
    when(posCatalog.attributesFor(any())).thenReturn(Map.of(rice, new ProductPos(rice, SoldBy.WEIGHT, "101", GstRates.of(5), "")));
    assertInvalid(req(Mode.PDF, 1, rice), "items[0].variantId");
  }

  @Test void aLabelThatDoesNotFitIsRefusedWithTheNeededHeight() {
    var f = new LabelFields(true, NameLanguage.BOTH, true, true, true, false, false, false, false, 7, Alignment.CENTER);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(new LabelTemplate(LabelTestData.TEMPLATE_ID, "tiny", Kind.THERMAL, 50, 20, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false));
    assertThatThrownBy(() -> service.print(req(Mode.PDF, 1, rice), actor)).isInstanceOf(ApiException.class).hasMessageContaining("does not fit");
  }

  @Test void pricePerUnit_isPrintedWhenThePackSizeCanBeRead_andOmittedWhenNot() {
    var f = new LabelFields(false, NameLanguage.NONE, true, false, true, false, false, false, true, 7, Alignment.CENTER);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(new LabelTemplate(LabelTestData.TEMPLATE_ID, "p", Kind.THERMAL, 60, 40, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false));
    assertThat(service.print(req(Mode.JSON, 1, rice), actor).json().toString()).contains("500 g  Rs 118.00/kg");
    org.mockito.Mockito.doReturn(List.of(product(rice, "Rice", "", "Family pack", 6500, 5900))).when(catalog).posProducts(any());
    assertThat(service.print(req(Mode.JSON, 1, rice), actor).json().toString()).contains("text=Family pack,").doesNotContain("/kg");
  }

  @Test void thermalModes_produceTheirContentTypes() {
    assertThat(new String(service.print(req(Mode.TSPL, 2, rice), actor).bytes(), StandardCharsets.ISO_8859_1)).contains("PRINT 2,1");
    assertThat(new String(service.print(req(Mode.ZPL, 2, rice), actor).bytes(), StandardCharsets.US_ASCII)).contains("^PQ2,0,1,N");
    assertThat(service.print(req(Mode.ESCPOS, 2, rice), actor).bytes()).isNotEmpty();
    assertThat(Mode.TSPL.contentType()).contains("text/plain");
    assertThat(Mode.PDF.filename()).isEqualTo("labels.pdf");
    assertThat(Mode.ESCPOS.contentType()).isEqualTo("application/octet-stream");
  }

  @Test void gujarati_whenTheFontEngineIsMissing_printerModesAndPdfFailClearly_jsonStillWorks() {
    var broken = mock(GujaratiRenderer.class);
    when(broken.available()).thenReturn(false);
    var f = new LabelFields(false, NameLanguage.BOTH, true, true, true, false, false, false, false, 7, Alignment.CENTER);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(new LabelTemplate(LabelTestData.TEMPLATE_ID, "g", Kind.THERMAL, 60, 50, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false));
    var svc = build(broken);
    for (Mode m : new Mode[] {Mode.TSPL, Mode.ZPL, Mode.ESCPOS}) {
      assertThatThrownBy(() -> svc.print(req(m, 1, rice), actor)).isInstanceOf(ApiException.class).hasMessage("Gujarati names need PDF or image mode");
    }
    assertThatThrownBy(() -> svc.print(req(Mode.PDF, 1, rice), actor)).isInstanceOf(ApiException.class).hasMessageContaining("use JSON mode");
    assertThat(svc.print(req(Mode.JSON, 1, rice), actor).json().toString()).contains("gujarati=true");
    assertThat(svc.print(req(Mode.ZPL, 1, sugar), actor).bytes()).isNotEmpty();
  }

  @Test void gujarati_withTheBundledFont_printsAsAPictureInEveryMode() {
    var f = new LabelFields(false, NameLanguage.BOTH, true, true, true, false, false, false, false, 7, Alignment.CENTER);
    when(templates.get(LabelTestData.TEMPLATE_ID)).thenReturn(new LabelTemplate(LabelTestData.TEMPLATE_ID, "g", Kind.THERMAL, 60, 50, 1, 1, 0, 0, 0, 0, BarcodeType.EAN13, f, false));
    assertThat(new String(service.print(req(Mode.ZPL, 1, rice), actor).bytes(), StandardCharsets.US_ASCII)).contains("^GFA,");
    assertThat(new String(service.print(req(Mode.TSPL, 1, rice), actor).bytes(), StandardCharsets.ISO_8859_1)).contains("BITMAP ");
  }

  @Test void history_clampsPaging() {
    var page = new Page<LabelHistoryRepository.Row>(List.of(), 0, 50, 0);
    when(history.page(null, 0, 50)).thenReturn(page);
    assertThat(service.history(null, -4, 500)).isSameAs(page);
  }

  @Test void noLabelLots_answersNothing() {
    assertThat(new NoLabelLots().lotFor(rice)).isEmpty();
  }

  @Test void dpi_isReadFromTheSettingOnEveryPrint() {
    String at203 = new String(service.print(req(Mode.ZPL, 1, rice), actor).bytes(), StandardCharsets.US_ASCII);
    when(settings.getInt(Settings.LABEL_DPI)).thenReturn(300);
    String at300 = new String(service.print(req(Mode.ZPL, 1, rice), actor).bytes(), StandardCharsets.US_ASCII);
    assertThat(at300).isNotEqualTo(at203);
  }
}
