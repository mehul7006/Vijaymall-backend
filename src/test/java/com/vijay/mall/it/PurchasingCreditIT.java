package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.files.FileStore;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/** Supplier statement, supplier credit (offset and refund), invoice attachment and purchase edits against real PostgreSQL. */
@SuppressWarnings({"unchecked", "rawtypes"})
class PurchasingCreditIT extends IntegrationTestBase {
  private static final String P = "/api/v1/admin/purchases";
  private static final String S = "/api/v1/admin/suppliers";
  private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

  @Autowired FileStore files;
  @Autowired PurchaseRepository purchaseRepo;

  private String admin() { return adminToken(); }

  private String key() { return UUID.randomUUID().toString(); }

  private long n(Object o) { return ((Number) o).longValue(); }

  private String sharedProduct;

  /** One product for the whole test class (every product added would crowd the seed products off the first page other tests read). */
  private synchronized String product() {
    if (sharedProduct == null) sharedProduct = newProduct();
    return sharedProduct;
  }

  private String newProduct() {
    String category = jdbc.queryForObject("select id::text from category limit 1", String.class);
    var res = post("/api/v1/admin/products", admin(), Map.of("categoryId", category, "nameEn", "Credit Item " + UUID.randomUUID(), "nameGu", "વસ્તુ",
        "mrpPaise", 20000, "pricePaise", 18000, "unit", "pack", "packSize", "1 kg", "qtyOnHand", 0));
    return (String) res.getBody().get("id");
  }

  /** A purchase without tax: qty x 10000 paise, fully paid or not. */
  private Map purchase(String supplier, int qty, boolean paid) {
    Map<String, Object> body = new HashMap<>(Map.of("supplierName", supplier, "gstMode", "NONE", "invoiceNo", "INV-" + UUID.randomUUID().toString().substring(0, 6),
        "items", List.of(Map.of("productId", product(), "qty", qty, "unitCostPaise", 10000))));
    if (paid) body.putAll(Map.of("paymentStatus", "PAID", "paymentMode", "CASH"));
    var res = call(HttpMethod.POST, P, admin(), body, key());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private Map giveBack(Map purchase, int qty) {
    Object line = ((List<Map>) purchase.get("items")).getFirst().get("id");
    var res = call(HttpMethod.POST, P + "/" + purchase.get("id") + "/returns", admin(),
        Map.of("items", List.of(Map.of("purchaseItemId", line, "qty", qty)), "reason", "damaged"), key());
    assertThat(res.getStatusCode()).as(String.valueOf(res.getBody())).isEqualTo(HttpStatus.CREATED);
    return res.getBody();
  }

  private ResponseEntityHolder credit(Object purchaseId, long amount, Object noteId, String k) {
    Map<String, Object> body = new HashMap<>(Map.of("amountPaise", amount, "mode", "CREDIT_NOTE"));
    if (noteId != null) body.put("debitNoteId", noteId);
    var res = call(HttpMethod.POST, P + "/" + purchaseId + "/payments", admin(), body, k);
    return new ResponseEntityHolder(res.getStatusCode(), res.getBody());
  }

  private record ResponseEntityHolder(org.springframework.http.HttpStatusCode status, Map body) {
    String code() { return (String) body.get("code"); }
  }

  private Map purchaseNow(Object id) { return get(P + "/" + id, admin()).getBody(); }

  private Map supplier(Map purchase) { return get(S + "/" + purchase.get("supplierId"), admin()).getBody(); }

  @Test
  void creditPaysAnotherPurchaseInPartThenExactlyAndNeverMoreThanTheCredit() {
    String name = "Credit Supplier " + UUID.randomUUID();
    Map p1 = purchase(name, 10, true);
    Map note = giveBack(p1, 4);
    assertThat(n(note.get("totalPaise"))).isEqualTo(40000);
    assertThat(n(purchaseNow(p1.get("id")).get("duePaise"))).isEqualTo(-40000);
    Map p2 = purchase(name, 5, false);
    assertThat(n(supplier(p2).get("balancePaise"))).isEqualTo(10000);
    assertThat(n(supplier(p2).get("creditPaise"))).isEqualTo(40000);

    assertThat(credit(p2.get("id"), 10000, null, key()).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(credit(p2.get("id"), 10000, UUID.randomUUID(), key()).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(credit(p2.get("id"), 50001, note.get("id"), key()).code()).isEqualTo("PAYMENT_EXCEEDS_DUE");
    assertThat(credit(p1.get("id"), 100, note.get("id"), key()).status()).as("a purchase cannot be paid with its own credit").isEqualTo(HttpStatus.BAD_REQUEST);

    String k = key();
    var part = credit(p2.get("id"), 30000, note.get("id"), k);
    assertThat(part.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(part.body()).containsEntry("paymentStatus", "PARTIAL");
    assertThat(n(part.body().get("duePaise"))).isEqualTo(20000);
    var payment = ((List<Map>) part.body().get("payments")).getFirst();
    assertThat(payment).containsEntry("mode", "CREDIT_NOTE").containsEntry("reference", note.get("noteNo"));
    assertThat(credit(p2.get("id"), 30000, note.get("id"), k).body()).as("a retry changes nothing").containsEntry("id", p2.get("id"));
    assertThat(n(purchaseNow(p1.get("id")).get("duePaise"))).isEqualTo(-10000);
    assertThat(n(supplier(p2).get("balancePaise"))).isEqualTo(10000);

    assertThat(credit(p2.get("id"), 10001, note.get("id"), key()).code()).isEqualTo("CREDIT_EXCEEDS_AVAILABLE");
    var exact = credit(p2.get("id"), 10000, note.get("id"), key());
    assertThat(exact.status()).isEqualTo(HttpStatus.CREATED);
    assertThat(n(purchaseNow(p1.get("id")).get("duePaise"))).isZero();
    assertThat(n(exact.body().get("duePaise"))).isEqualTo(10000);
    assertThat(credit(p2.get("id"), 1, note.get("id"), key()).code()).isEqualTo("CREDIT_EXCEEDS_AVAILABLE");
    assertThat(n(supplier(p2).get("creditPaise"))).isZero();
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'PURCHASE_CREDIT_USE' and entity_id = ?", Long.class, p2.get("id").toString())).isEqualTo(2L);

    Map p3 = purchase(name, 1, true);
    assertThat(credit(p3.get("id"), 100, note.get("id"), key()).code()).isEqualTo("ALREADY_PAID");
    assertThat(credit(UUID.randomUUID(), 100, note.get("id"), key()).status()).isEqualTo(HttpStatus.NOT_FOUND);
    Map other = purchase("Other " + UUID.randomUUID(), 1, false);
    assertThat(credit(other.get("id"), 100, note.get("id"), key()).status()).as("the note belongs to another supplier").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, P + "/" + p2.get("id") + "/payments", admin(), Map.of("amountPaise", 1, "mode", "CREDIT_NOTE", "paidAt", "2999-01-01T00:00:00Z",
        "debitNoteId", note.get("id")), key()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void twoRequestsCannotUseTheSameCreditTwice() {
    String name = "Race Supplier " + UUID.randomUUID();
    Map p1 = purchase(name, 10, true);
    Map note = giveBack(p1, 4);
    Map a = purchase(name, 3, false);
    Map b = purchase(name, 3, false);
    var first = CompletableFuture.supplyAsync(() -> credit(a.get("id"), 30000, note.get("id"), key()));
    var second = CompletableFuture.supplyAsync(() -> credit(b.get("id"), 30000, note.get("id"), key()));
    var results = List.of(first.join(), second.join());
    assertThat(results.stream().filter(r -> r.status().equals(HttpStatus.CREATED)).count()).isEqualTo(1);
    assertThat(results.stream().filter(r -> "CREDIT_EXCEEDS_AVAILABLE".equals(r.body().get("code"))).count()).isEqualTo(1);
    assertThat(n(purchaseNow(p1.get("id")).get("duePaise"))).isEqualTo(-10000);
    assertThat(n(supplier(a).get("balancePaise"))).isEqualTo(20000);
  }

  @Test
  void aRefundTakesTheCreditOutInCashAndTheStatementShowsEverythingAndAddsUpToThePayables() {
    String name = "Refund Supplier " + UUID.randomUUID();
    Map p1 = purchase(name, 10, true);
    giveBack(p1, 4);
    Map p3 = purchase(name, 2, true);
    giveBack(p3, 1);
    Object id = p1.get("supplierId");
    assertThat(n(get(S + "/" + id, admin()).getBody().get("creditPaise"))).isEqualTo(50000);
    String account = UUID.randomUUID().toString();
    Map<String, Object> body = Map.of("amountPaise", 45000, "mode", "BANK", "reference", "UTR77", "moneyAccountId", account);

    var tooMuch = call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), Map.of("amountPaise", 50001, "mode", "CASH"), key());
    assertThat(tooMuch.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(tooMuch.getBody()).containsEntry("code", "REFUND_EXCEEDS_CREDIT");
    assertThat(call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), Map.of("amountPaise", 1, "mode", "GIFT"), key()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), Map.of("amountPaise", 1, "mode", "CASH", "receivedAt", "2999-01-01T00:00:00Z"), key())
        .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.POST, S + "/" + UUID.randomUUID() + "/refunds", admin(), body, key()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), body, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    String k = key();
    var ok = call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), body, k);
    assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(n(ok.getBody().get("creditPaise"))).isEqualTo(5000);
    assertThat(n(ok.getBody().get("balancePaise"))).isEqualTo(-5000);
    assertThat(ok.getBody()).containsEntry("moneyAccountId", account);
    var replay = call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), body, k);
    assertThat(replay.getBody()).containsEntry("id", ok.getBody().get("id"));
    assertThat(call(HttpMethod.POST, S + "/" + id + "/refunds", admin(), Map.of("amountPaise", 46000, "mode", "BANK"), k).getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(jdbc.queryForObject("select count(*) from supplier_refund where supplier_id = ?::uuid", Long.class, id)).isEqualTo(1L);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'SUPPLIER_REFUND' and entity_id = ?", Long.class, id.toString())).isEqualTo(1L);
    var list = get(S + "/" + id + "/refunds", admin()).getBody().get("_array");
    assertThat((List<Map>) list).singleElement().satisfies(r -> assertThat(r).containsEntry("reference", "UTR77"));
    assertThat(get(S + "/" + UUID.randomUUID() + "/refunds", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // the statement: everything in date order, and the closing balance is the payables figure for this supplier
    Map st = get(S + "/" + id + "/statement?size=50", admin()).getBody();
    List<Map> rows = (List<Map>) ((Map) st.get("rows")).get("items");
    assertThat(rows.stream().map(r -> r.get("kind")).distinct().toList()).containsExactly("PURCHASE", "PAYMENT", "DEBIT_NOTE", "SUPPLIER_REFUND");
    assertThat(rows).hasSize(2 + 2 + 2 + 1);
    assertThat(n(st.get("openingBalancePaise"))).isZero();
    long sum = rows.stream().mapToLong(r -> n(r.get("amountPaise"))).sum();
    assertThat(n(st.get("closingBalancePaise"))).isEqualTo(sum).isEqualTo(-5000);
    assertThat(n(rows.getLast().get("balancePaise"))).isEqualTo(-5000);
    long running = 0;
    for (Map r : rows) {
      running += n(r.get("amountPaise"));
      assertThat(n(r.get("balancePaise"))).isEqualTo(running);
    }
    var mine = purchaseRepo.payablesBySupplier(TODAY, 100_000).stream().filter(s -> id.equals(s.supplierId().toString())).findFirst().orElseThrow();
    assertThat(mine.duePaise()).isEqualTo(n(st.get("closingBalancePaise")));
    assertThat(n(get(S + "/" + id, admin()).getBody().get("totalDuePaise"))).isEqualTo(-5000);
  }

  @Test
  void aStatementHasAnOpeningBalanceForEarlierDaysPagesAndRefusesABadRange() {
    String name = "Statement Supplier " + UUID.randomUUID();
    Map p1 = purchase(name, 10, true);
    Map note = giveBack(p1, 4);
    Map p2 = purchase(name, 5, false);
    credit(p2.get("id"), 20000, note.get("id"), key());
    Object id = p1.get("supplierId");
    jdbc.update("update purchase set invoice_date = ? where id = ?::uuid", TODAY.minusDays(10), p1.get("id").toString());
    jdbc.update("update purchase_payment set paid_at = now() - interval '10 days' where purchase_id = ?::uuid", p1.get("id").toString());
    jdbc.update("update purchase_return set created_at = now() - interval '9 days' where id = ?::uuid", note.get("id").toString());

    Map st = get(S + "/" + id + "/statement?from=" + TODAY.minusDays(5), admin()).getBody();
    assertThat(n(st.get("openingBalancePaise"))).as("100000 bought, 100000 paid, 40000 returned").isEqualTo(-40000);
    List<Map> rows = (List<Map>) ((Map) st.get("rows")).get("items");
    assertThat(rows.stream().map(r -> r.get("kind")).toList()).containsExactly("PURCHASE", "PAYMENT", "CREDIT_USED");
    assertThat(n(rows.getFirst().get("balancePaise"))).isEqualTo(-40000 + 50000);
    assertThat(n(st.get("closingBalancePaise"))).isEqualTo(10000);
    assertThat(n(supplier(p2).get("balancePaise"))).isEqualTo(n(st.get("closingBalancePaise")));

    Map old = get(S + "/" + id + "/statement?to=" + TODAY.minusDays(5), admin()).getBody();
    assertThat(n(old.get("closingBalancePaise"))).isEqualTo(-40000);
    assertThat(((Map) old.get("rows")).get("total")).isEqualTo(3);

    Map paged = get(S + "/" + id + "/statement?size=2&page=1", admin()).getBody();
    assertThat((List<Map>) ((Map) paged.get("rows")).get("items")).hasSize(2);
    assertThat(((Map) paged.get("rows")).get("hasMore")).isEqualTo(true);
    assertThat(n(((List<Map>) ((Map) paged.get("rows")).get("items")).getFirst().get("balancePaise"))).isNotZero();

    assertThat(get(S + "/" + id + "/statement?from=" + TODAY + "&to=" + TODAY.minusDays(1), admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get(S + "/" + UUID.randomUUID() + "/statement", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
  private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

  private org.springframework.http.ResponseEntity<String> upload(Object id, byte[] content, String fileName) {
    var form = new LinkedMultiValueMap<String, Object>();
    form.add("file", new ByteArrayResource(content) { @Override public String getFilename() { return fileName; } });
    return client().post().uri(P + "/" + id + "/attachment").headers(h -> h.setBearerAuth(admin())).contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form).retrieve().toEntity(String.class);
  }

  @Test
  void anInvoiceFileIsStoredPrivatelyServedInlineReplacedAndRemoved() {
    Map p = purchase("Attach Supplier " + UUID.randomUUID(), 1, false);
    Object id = p.get("id");
    assertThat(p).containsEntry("hasAttachment", false);
    assertThat(p.get("attachmentUrl")).isNull();
    assertThat(get(P + "/" + id + "/attachment", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(upload(id, PDF, "scan.pdf").getStatusCode()).isEqualTo(HttpStatus.OK);
    String first = jdbc.queryForObject("select attachment_file from purchase where id = ?::uuid", String.class, id.toString());
    assertThat(first).matches("[0-9a-f-]{36}\\.pdf");
    Map view = purchaseNow(id);
    assertThat(view).containsEntry("hasAttachment", true).containsEntry("attachmentUrl", P + "/" + id + "/attachment");
    var served = client().get().uri(P + "/" + id + "/attachment").headers(h -> h.setBearerAuth(admin())).retrieve().toEntity(byte[].class);
    assertThat(served.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(served.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    assertThat(served.getHeaders().getFirst("Content-Disposition")).startsWith("inline").contains("invoice-" + id + ".pdf");
    assertThat(served.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(served.getBody()).isEqualTo(PDF);
    assertThat(client().get().uri("/img/purchase-invoices/" + first).retrieve().toEntity(String.class).getStatusCode().value()).isNotEqualTo(200);
    assertThat(client().get().uri(P + "/" + id + "/attachment").retrieve().toEntity(String.class).getStatusCode().value()).isIn(401, 403);

    assertThat(upload(id, PNG, "x.pdf").getStatusCode()).as("the type is decided by the bytes, not the name").isEqualTo(HttpStatus.OK);
    assertThat(files.read("purchase-invoices", first)).as("the old file goes after the replace").isEmpty();
    String second = jdbc.queryForObject("select attachment_file from purchase where id = ?::uuid", String.class, id.toString());
    assertThat(second).endsWith(".png");
    assertThat(files.read("purchase-invoices", second)).isPresent();
    assertThat(upload(id, JPEG, "y.png").getStatusCode()).isEqualTo(HttpStatus.OK);

    var bad = upload(id, "just text".getBytes(StandardCharsets.UTF_8), "x.png");
    assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(bad.getBody()).contains("UNSUPPORTED_FILE");
    assertThat(upload(id, new byte[0], "e.pdf").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    byte[] big = new byte[6 * 1024 * 1024];
    System.arraycopy(PDF, 0, big, 0, PDF.length);
    var tooBig = upload(id, big, "big.pdf");
    assertThat(tooBig.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(tooBig.getBody()).contains("FILE_TOO_LARGE");
    assertThat(upload(UUID.randomUUID(), PDF, "z.pdf").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    String third = jdbc.queryForObject("select attachment_file from purchase where id = ?::uuid", String.class, id.toString());
    var removed = call(HttpMethod.DELETE, P + "/" + id + "/attachment", admin(), null, null);
    assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(removed.getBody()).containsEntry("hasAttachment", false);
    assertThat(files.read("purchase-invoices", third)).isEmpty();
    assertThat(call(HttpMethod.DELETE, P + "/" + id + "/attachment", admin(), null, null).getStatusCode()).as("removing again is harmless").isEqualTo(HttpStatus.OK);
    assertThat(call(HttpMethod.DELETE, P + "/" + UUID.randomUUID() + "/attachment", admin(), null, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'PURCHASE_ATTACHMENT' and entity_id = ?", Long.class, id.toString())).isEqualTo(4L);
  }

  private ResponseEntityHolder patch(Object id, Map<String, Object> body) {
    var res = call(HttpMethod.PATCH, P + "/" + id, admin(), body, null);
    return new ResponseEntityHolder(res.getStatusCode(), res.getBody());
  }

  @Test
  void onlyTheNonStockFieldsOfARecordedPurchaseCanBeEditedAndEveryEditIsAudited() {
    String name = "Edit Supplier " + UUID.randomUUID();
    Map first = purchase(name, 2, false);
    Object id = first.get("id");
    Map<String, Object> change = new HashMap<>();
    change.put("invoiceNo", "NEW-1");
    change.put("invoiceDate", TODAY.minusDays(3).toString());
    change.put("dueDate", TODAY.plusDays(20).toString());
    change.put("note", "checked");
    change.put("attachmentNote", "see folder 4");
    change.put("supplierGstin", "24ABCDE1234F1Z5");
    var ok = patch(id, change);
    assertThat(ok.status()).isEqualTo(HttpStatus.OK);
    assertThat(ok.body()).containsEntry("invoiceNo", "NEW-1").containsEntry("invoiceDate", TODAY.minusDays(3).toString()).containsEntry("note", "checked")
        .containsEntry("attachmentNote", "see folder 4").containsEntry("supplierGstin", "24ABCDE1234F1Z5").containsEntry("supplierStateCode", "24")
        .containsEntry("dueDate", TODAY.plusDays(20).toString());
    assertThat(n(ok.body().get("totalCostPaise"))).isEqualTo(n(first.get("totalCostPaise")));
    String detail = jdbc.queryForObject("select change from audit_log where action = 'PURCHASE_EDIT' and entity_id = ?", String.class, id.toString());
    assertThat(detail).contains("invoiceNo: '" + first.get("invoiceNo") + "' -> 'NEW-1'").contains("note: '' -> 'checked'");
    assertThat(patch(id, change).status()).as("the same values again").isEqualTo(HttpStatus.OK);
    assertThat(jdbc.queryForObject("select count(*) from audit_log where action = 'PURCHASE_EDIT' and entity_id = ?", Long.class, id.toString())).isEqualTo(1L);
    assertThat(supplier(first)).as("the supplier master is untouched").containsEntry("gstin", "");

    assertThat(patch(id, Map.of("dueDate", 5)).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> clear = new HashMap<>();
    clear.put("dueDate", null);
    clear.put("note", "");
    var cleared = patch(id, clear).body();
    assertThat(cleared).containsEntry("note", "");
    assertThat(cleared.get("dueDate")).isNull();

    for (String locked : List.of("qty", "items", "totalCostPaise", "unitCostPaise", "gstMode", "supplierId", "paidPaise")) {
      var r = patch(id, Map.of(locked, 1));
      assertThat(r.status()).as(locked).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(r.body().toString()).contains("purchase return");
    }
    assertThat(patch(id, Map.of()).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("invoiceDate", TODAY.plusDays(1).toString())).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("invoiceDate", "yesterday")).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("dueDate", TODAY.minusDays(30).toString())).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("invoiceNo", 5)).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("note", "x".repeat(201))).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("supplierGstin", "BAD")).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(id, Map.of("supplierStateCode", "27")).status()).as("does not match the GSTIN").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(patch(UUID.randomUUID(), Map.of("note", "x")).status()).isEqualTo(HttpStatus.NOT_FOUND);

    Map second = purchase(name, 1, false);
    var dup = patch(second.get("id"), Map.of("invoiceNo", " new-1 "));
    assertThat(dup.status()).isEqualTo(HttpStatus.OK);
    assertThat(dup.body().get("warnings").toString()).contains("invoice number new-1 was already recorded");
    var clean = patch(second.get("id"), Map.of("invoiceNo", "UNIQUE-" + UUID.randomUUID()));
    assertThat((List) clean.body().get("warnings")).isEmpty();
  }
}
