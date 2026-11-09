package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** Store barcodes, label templates, label printing in every mode, the print history and who may use them, against real PostgreSQL. */
@SuppressWarnings({"unchecked", "rawtypes"})
class LabelPrintingIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";
  private static final String THERMAL_50X25 = "00000000-0000-0000-0000-00000000a001";
  private static final String SHEET_A4 = "00000000-0000-0000-0000-00000000a003";

  private String category(String admin) {
    return ((List<Map<String, Object>>) getList("/api/v1/categories", admin)).get(0).get("id").toString();
  }

  private String newProduct(String admin, int stock) {
    Map<String, Object> b = new HashMap<>(Map.of("categoryId", category(admin), "nameEn", "Label rice " + UUID.randomUUID(), "nameGu", "ચોખા",
        "mrpPaise", 6500, "pricePaise", 5900, "unit", "pack", "packSize", "500 g", "qtyOnHand", stock));
    var res = post("/api/v1/admin/products", admin, b);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    return res.getBody().get("id").toString();
  }

  private byte[] raw(String token, Object body) {
    var res = client().post().uri("/api/v1/admin/labels").headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON).body(body)
        .retrieve().toEntity(byte[].class);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    lastType = res.getHeaders().getContentType();
    lastHeaders = res.getHeaders();
    return res.getBody();
  }

  private MediaType lastType;
  private org.springframework.http.HttpHeaders lastHeaders;

  @Test void generatedBarcodeIsSavedAndTheCounterScanFindsTheVariant() {
    String admin = adminToken();
    String id = newProduct(admin, 5);
    var first = post("/api/v1/admin/variants/" + id + "/barcode/generate", admin, null);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    String code = (String) first.getBody().get("barcode");
    assertThat(code).hasSize(13).startsWith("29");
    assertThat(first.getBody()).containsEntry("created", true);

    var scan = get("/api/v1/pos/barcode/" + code, token(CASHIER));
    assertThat(scan.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map) scan.getBody().get("product")).get("productId")).isEqualTo(id);

    var again = post("/api/v1/admin/variants/" + id + "/barcode/generate", token(CASHIER), null);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(again.getBody()).containsEntry("barcode", code).containsEntry("created", false);
    assertThat(((List) again.getBody().get("barcodes"))).containsExactly(code);
    assertThat(jdbc.queryForObject("select count(*) from product_barcode where product_id = ?::uuid", Long.class, id)).isEqualTo(1);
  }

  @Test void twoVariantsGetDifferentCodes_andUnknownOrWeighedVariantsAreRefused() {
    String admin = adminToken();
    String a = newProduct(admin, 5);
    String b = newProduct(admin, 5);
    String codeA = (String) post("/api/v1/admin/variants/" + a + "/barcode/generate", admin, null).getBody().get("barcode");
    String codeB = (String) post("/api/v1/admin/variants/" + b + "/barcode/generate", admin, null).getBody().get("barcode");
    assertThat(codeA).isNotEqualTo(codeB);
    assertThat(post("/api/v1/admin/variants/" + UUID.randomUUID() + "/barcode/generate", admin, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    var put = call(HttpMethod.PUT, "/api/v1/admin/products/" + b + "/pos", admin, Map.of("soldBy", "WEIGHT", "plu", "777", "gstPercent", 5, "barcodes", List.of()), null);
    assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
    var weighed = post("/api/v1/admin/variants/" + b + "/barcode/generate", admin, null);
    assertThat(weighed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void addingABarcodeWithAWrongCheckDigitIsRefusedButOtherLengthsPass() {
    String admin = adminToken();
    String id = newProduct(admin, 5);
    var bad = call(HttpMethod.PUT, "/api/v1/admin/products/" + id + "/pos", admin, Map.of("soldBy", "UNIT", "gstPercent", 5, "barcodes", List.of("4006381333932")), null);
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map) ((Map) bad.getBody().get("details")).get("fields")).get("barcodes").toString()).contains("wrong last digit");
    String text = "LBL-" + UUID.randomUUID().toString().substring(0, 8);
    var ok = call(HttpMethod.PUT, "/api/v1/admin/products/" + id + "/pos", admin,
        Map.of("soldBy", "UNIT", "gstPercent", 5, "barcodes", List.of("96385074", text, "12345")), null);
    assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.OK);
    jdbc.update("delete from product_barcode where product_id = ?::uuid", id);
  }

  @Test void printPdfReturnsAPdfWithOnePagePerThermalLabel_andWritesHistory() throws Exception {
    String admin = adminToken();
    String id = newProduct(admin, 7);
    byte[] pdf = raw(admin, Map.of("templateId", THERMAL_50X25, "mode", "PDF", "items", List.of(Map.of("variantId", id, "copies", 3))));
    assertThat(lastType.toString()).isEqualTo("application/pdf");
    assertThat(lastHeaders.getFirst("X-Label-Count")).isEqualTo("3");
    try (var doc = Loader.loadPDF(pdf)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(3);
    }
    assertThat(jdbc.queryForObject("select count(*) from product_barcode where product_id = ?::uuid", Long.class, id)).as("barcode generated by the print").isEqualTo(1);

    var history = get("/api/v1/admin/labels/history?variantId=" + id, admin);
    assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
    var rows = (List<Map<String, Object>>) history.getBody().get("items");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("copies", 3).containsEntry("mode", "PDF").containsEntry("templateName", "50 x 25 mm thermal");
    assertThat(rows.get(0)).containsKey("actorName");
    assertThat(history.getBody()).containsKeys("total", "hasMore", "page", "size");
    assertThat(jdbc.queryForObject("select count(*) from audit_log where entity = 'label' and action = 'PRINT'", Long.class)).isPositive();
  }

  @Test void fromStockPrintsOneLabelPerUnit_andSheetStartPositionWorks() throws Exception {
    String admin = adminToken();
    String id = newProduct(admin, 6);
    byte[] pdf = raw(token(CASHIER), Map.of("templateId", SHEET_A4, "mode", "PDF", "startPosition", 23,
        "items", List.of(Map.of("variantId", id, "copies", "fromStock"))));
    assertThat(lastHeaders.getFirst("X-Label-Count")).isEqualTo("6");
    assertThat(lastHeaders.getFirst("X-Label-Pages")).isEqualTo("2");
    try (var doc = Loader.loadPDF(pdf)) {
      assertThat(doc.getNumberOfPages()).isEqualTo(2);
    }
  }

  @Test void printerLanguagesAndJson() {
    String admin = adminToken();
    String id = newProduct(admin, 2);
    Map<String, Object> body = new HashMap<>(Map.of("templateId", THERMAL_50X25, "items", List.of(Map.of("variantId", id, "copies", 2))));
    body.put("mode", "TSPL");
    String tspl = new String(raw(admin, body), StandardCharsets.ISO_8859_1);
    assertThat(tspl).startsWith("SIZE 50 mm,25 mm").contains("PRINT 2,1").contains("EAN13");
    body.put("mode", "ZPL");
    assertThat(new String(raw(admin, body), StandardCharsets.US_ASCII)).startsWith("^XA").contains("^PQ2,0,1,N").contains("^BEN");
    body.put("mode", "ESCPOS");
    assertThat(raw(admin, body)).isNotEmpty();
    assertThat(lastType.toString()).isEqualTo("application/octet-stream");
    body.put("mode", "JSON");
    var json = call(HttpMethod.POST, "/api/v1/admin/labels", admin, body, null);
    assertThat(json.getStatusCode()).isEqualTo(HttpStatus.OK);
    var items = (List<Map<String, Object>>) json.getBody().get("items");
    assertThat(items.get(0)).containsEntry("copies", 2);
    assertThat(((Map) items.get(0).get("barcode")).get("payload").toString()).hasSize(13);
    assertThat((List) items.get(0).get("elements")).isNotEmpty();
    assertThat(json.getBody()).containsEntry("dpi", 203).containsEntry("totalLabels", 2);
  }

  @Test void badRequestsAreFieldErrors() {
    String admin = adminToken();
    String id = newProduct(admin, 2);
    var tooMany = post("/api/v1/admin/labels", admin, Map.of("templateId", THERMAL_50X25, "mode", "PDF", "items", List.of(Map.of("variantId", id, "copies", 501))));
    assertThat(tooMany.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(((Map) ((Map) tooMany.getBody().get("details")).get("fields")).keySet()).contains("items[0].copies");
    var sheetThermal = post("/api/v1/admin/labels", admin, Map.of("templateId", SHEET_A4, "mode", "ZPL", "items", List.of(Map.of("variantId", id, "copies", 1))));
    assertThat(sheetThermal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/labels", admin, Map.of("templateId", UUID.randomUUID(), "mode", "PDF", "items", List.of(Map.of("variantId", id, "copies", 1)))).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(post("/api/v1/admin/labels", admin, Map.of("templateId", THERMAL_50X25, "mode", "PDF", "items", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post("/api/v1/admin/labels", admin, Map.of("templateId", THERMAL_50X25, "mode", "FAX", "items", List.of(Map.of("variantId", id, "copies", 1)))).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void templatesCrud_builtInsAreFixed_staffCanReadButNotChange() {
    String admin = adminToken();
    var list = get("/api/v1/admin/label-templates", admin);
    assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat((List<Map<String, Object>>) list.getBody().get("items")).extracting(m -> m.get("name")).contains("50 x 25 mm thermal", "38 x 25 mm thermal", "A4 sheet 3 x 8");
    assertThat(get("/api/v1/admin/label-templates", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/admin/label-templates/" + THERMAL_50X25, token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> body = new HashMap<>(Map.of("name", "Mine " + UUID.randomUUID().toString().substring(0, 6), "kind", "THERMAL", "widthMm", 60, "heightMm", 40,
        "barcodeType", "CODE128", "fields", fieldsBody()));
    assertThat(post("/api/v1/admin/label-templates", token(CASHIER), body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    var created = post("/api/v1/admin/label-templates", admin, body);
    assertThat(created.getStatusCode()).as(String.valueOf(created.getBody())).isEqualTo(HttpStatus.CREATED);
    String id = (String) created.getBody().get("id");
    assertThat(post("/api/v1/admin/label-templates", admin, body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    body.put("heightMm", 8);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/label-templates/" + id, admin, body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    body.put("heightMm", 45);
    var updated = call(HttpMethod.PUT, "/api/v1/admin/label-templates/" + id, admin, body, null);
    assertThat(updated.getBody()).containsEntry("heightMm", 45.0);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/label-templates/" + THERMAL_50X25, admin, body, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/label-templates/" + THERMAL_50X25, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/label-templates/" + id, token(CASHIER), null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/label-templates/" + id, admin, null, null).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(get("/api/v1/admin/label-templates/" + id, admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test void fiveHundredLabelsAndTheMaximumRequestRenderInAFewSeconds() throws Exception {
    String admin = adminToken();
    String id = newProduct(admin, 1);
    for (String template : new String[] {SHEET_A4, THERMAL_50X25}) {
      long start = System.nanoTime();
      byte[] pdf = raw(admin, Map.of("templateId", template, "mode", "PDF", "items", List.of(Map.of("variantId", id, "copies", 500))));
      long ms = (System.nanoTime() - start) / 1_000_000;
      System.out.println("LABEL PDF 500 labels " + template + ": " + ms + " ms, " + pdf.length + " bytes");
      try (var doc = Loader.loadPDF(pdf)) {
        assertThat(doc.getNumberOfPages()).isEqualTo(template.equals(SHEET_A4) ? 21 : 500);
      }
      assertThat(ms).isLessThan(5000);
    }
    long start = System.nanoTime();
    var items = List.of(Map.of("variantId", id, "copies", 500), Map.of("variantId", id, "copies", 500), Map.of("variantId", id, "copies", 500),
        Map.of("variantId", id, "copies", 500));
    byte[] pdf = raw(admin, Map.of("templateId", THERMAL_50X25, "mode", "PDF", "items", items));
    System.out.println("LABEL PDF 2000 thermal labels: " + (System.nanoTime() - start) / 1_000_000 + " ms, " + pdf.length + " bytes");
    assertThat(lastHeaders.getFirst("X-Label-Count")).isEqualTo("2000");
  }

  private static Map<String, Object> fieldsBody() {
    Map<String, Object> f = new HashMap<>(Map.of("shopName", false, "productName", "BOTH", "packSize", true, "mrp", false, "price", true, "packedOn", false,
        "bestBefore", false, "batchNo", false, "pricePerUnit", false, "fontSize", 8));
    f.put("alignment", "LEFT");
    return f;
  }

  @Test void customersAndGuestsCannotUseAnyOfIt() {
    String customer = token(freshEmail());
    String id = UUID.randomUUID().toString();
    Map<String, Object> print = Map.of("templateId", THERMAL_50X25, "mode", "PDF", "items", List.of(Map.of("variantId", id, "copies", 1)));
    assertThat(post("/api/v1/admin/labels", customer, print).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/admin/labels", null, print).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/admin/labels/history", customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/label-templates", customer).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post("/api/v1/admin/variants/" + id + "/barcode/generate", customer, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }
}
