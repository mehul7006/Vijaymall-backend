package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.gst.app.GstService;
import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The owner's edits of the GST master against real PostgreSQL: the new rate shows in {@code /gst/suggest} at once, both audit trails
 * are written, deactivate and reactivate, the in-use guard, field validation, and a second server noticing an edit. Every test puts the
 * shared seed back (the reads in {@link GstMasterIT} count on it).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class GstAdminIT extends IntegrationTestBase {
  private static final String ROWS = "/api/v1/admin/gst-master/rows";
  @Autowired GstService gst;
  private String admin;

  @BeforeEach
  void token() { admin = adminToken(); }

  /** Removes rows added here, switches every row on again and rebuilds the index. */
  @AfterEach
  void restoreTheSeed() {
    jdbc.update("delete from gst_keyword where row_no > 5950");
    jdbc.update("delete from gst_hsn_rate where row_no > 564");
    jdbc.update("update gst_hsn_rate set active = true, updated_at = now() where not active");
    gst.reloadIfChanged();
  }

  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

  private Map<String, Object> best(String name) {
    return (Map<String, Object>) get("/api/v1/gst/suggest?name=" + enc(name), admin).getBody().get("best");
  }

  private Map<String, Object> row(String path) { return ((List<Map<String, Object>>) get(path, admin).getBody().get("items")).get(0); }

  /** The editable fields of a row as the edit endpoints take them, with the rate replaced. */
  private static Map<String, Object> body(Map<String, Object> row, Object rate) {
    Map<String, Object> b = new HashMap<>();
    b.put("hsn", row.get("hsn"));
    b.put("description", row.get("description"));
    b.put("gstPercent", rate);
    b.put("taxType", row.get("taxType"));
    return b;
  }

  private ResponseEntity<Map> put(String id, Object body) { return call(HttpMethod.PUT, ROWS + "/" + id, admin, body, null); }

  private ResponseEntity<Map> delete(String id) { return call(HttpMethod.DELETE, ROWS + "/" + id, admin, null, null); }

  @Test void aRateEditShowsInSuggestAtOnceAndLeavesBothAuditTrails() {
    Map<String, Object> shampoo = best("shampoo");
    String id = (String) shampoo.get("rowId");
    assertThat(new BigDecimal(shampoo.get("gstPercent").toString())).isEqualByComparingTo("5");
    String before = client().get().uri("/api/v1/gst/hsn?q=3305").headers(h -> h.setBearerAuth(admin)).retrieve().toEntity(String.class).getHeaders().getETag();

    Map<String, Object> edit = body(shampoo, 18);
    edit.put("verification", "VERIFIED");
    ResponseEntity<Map> res = put(id, edit);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) res.getBody().get("gstPercent")).intValue()).isEqualTo(18);
    assertThat(((Number) res.getBody().get("cgstRate")).intValue()).isEqualTo(9);
    assertThat(((Number) best("shampoo").get("gstPercent")).intValue()).isEqualTo(18);

    Map<String, Object> audit = jdbc.queryForMap("select actor_id, action, entity, old_value->>'gstPercent' as o, new_value->>'gstPercent' as n, new_value->>'verification' as v "
        + "from gst_master_audit where entity_id = ?::uuid order by at desc limit 1", id);
    assertThat(audit).containsEntry("action", "UPDATE").containsEntry("entity", "HSN_RATE").containsEntry("o", "5").containsEntry("n", "18").containsEntry("v", "VERIFIED");
    assertThat(audit.get("actor_id")).isNotNull();
    assertThat(jdbc.queryForObject("select change from audit_log where entity = 'gst_hsn_rate' and entity_id = ? order by created_at desc limit 1", String.class, id))
        .contains("hsn 3305").contains("5%").contains("18%");

    ResponseEntity<String> changed = client().get().uri("/api/v1/gst/hsn?q=3305").headers(h -> { h.setBearerAuth(admin); h.set(HttpHeaders.IF_NONE_MATCH, before); })
        .retrieve().toEntity(String.class);
    assertThat(changed.getStatusCode()).as("an ETag from before the edit no longer matches").isEqualTo(HttpStatus.OK);

    Map<String, Object> back = body(shampoo, 5);
    back.put("verification", shampoo.get("verification"));
    assertThat(put(id, back).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Number) best("shampoo").get("gstPercent")).intValue()).isEqualTo(5);
    assertThat(jdbc.queryForObject("select count(*) from gst_master_audit where entity_id = ?::uuid", Long.class, id)).isEqualTo(2);
  }

  @Test void aNewRowWithItemNamesIsSuggestedAtOnceAsToBeVerified() {
    Map<String, Object> in = new HashMap<>(Map.of("hsn", "7777", "description", "Test snack", "gstPercent", 5, "taxType", "TAXABLE", "keywords", List.of("my test snack")));
    ResponseEntity<Map> res = post(ROWS, admin, in);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(res.getBody().get("hsn")).isEqualTo("7777");
    assertThat(res.getBody().get("condition")).isEqualTo("ANY");
    assertThat(res.getBody().get("active")).isEqualTo(true);
    Map<String, Object> hit = best("my test snack");
    assertThat(hit.get("hsn")).isEqualTo("7777");
    assertThat(hit.get("verification")).isEqualTo("VERIFY_PRODUCT");
    assertThat(post(ROWS, admin, in).getStatusCode()).as("the same name is not added twice").isEqualTo(HttpStatus.CREATED);
    assertThat(jdbc.queryForObject("select count(*) from gst_keyword where hsn = '7777'", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from gst_master_audit where action = 'CREATE' and new_value->>'hsn' = '7777'", Long.class)).isEqualTo(2);
  }

  @Test void everyWrongFieldIsReportedInOne400() {
    Map<String, Object> bad = new HashMap<>(Map.of("hsn", "12", "gstPercent", 101, "taxType", "ZERO"));
    ResponseEntity<Map> res = post(ROWS, admin, bad);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(res.getBody().get("code")).isEqualTo("VALIDATION_FAILED");
    assertThat((Map<String, Object>) ((Map) res.getBody().get("details")).get("fields")).containsOnlyKeys("hsn", "description", "gstPercent", "taxType");
    for (Map.Entry<String, Object> one : Map.<String, Object>of("gstPercent", 5.5555, "cessRate", -1).entrySet()) {
      Map<String, Object> b = new HashMap<>(Map.of("hsn", "7777", "description", "x", "gstPercent", 5, "taxType", "TAXABLE"));
      b.put(one.getKey(), one.getValue());
      ResponseEntity<Map> r = post(ROWS, admin, b);
      assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat((Map<String, Object>) ((Map) r.getBody().get("details")).get("fields")).containsKey(one.getKey());
    }
    assertThat(post(ROWS, admin, Map.of("hsn", "7777", "description", "x", "gstPercent", 5, "taxType", "NIL")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(jdbc.queryForObject("select count(*) from gst_hsn_rate where row_no > 564", Long.class)).isZero();
  }

  @Test void deactivateKeepsTheRowListedSuggestStillAnswersAndSavingSwitchesItBackOn() {
    Map<String, Object> uht = best("uht milk");
    String id = (String) uht.get("rowId");
    assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(delete(id).getStatusCode()).as("a second DELETE is the same").isEqualTo(HttpStatus.NO_CONTENT);
    List<Map<String, Object>> rows = (List<Map<String, Object>>) get("/api/v1/gst/hsn/0401", admin).getBody().get("items");
    assertThat(rows).filteredOn(r -> r.get("rowId").equals(id)).singleElement().satisfies(r -> assertThat(r.get("active")).isEqualTo(false));
    Map<String, Object> after = best("uht milk");
    assertThat(after.get("hsn")).isEqualTo("0401");
    assertThat(after.get("rowId")).isNotEqualTo(id);
    assertThat(jdbc.queryForObject("select count(*) from gst_master_audit where entity_id = ?::uuid and action = 'DEACTIVATE'", Long.class, id)).isEqualTo(1);

    ResponseEntity<Map> on = put(id, body(uht, 0));
    assertThat(on.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(on.getBody().get("active")).isEqualTo(true);
    assertThat(best("uht milk").get("rowId")).isEqualTo(id);
    assertThat(jdbc.queryForObject("select count(*) from gst_master_audit where entity_id = ?::uuid and action = 'ACTIVATE'", Long.class, id)).isEqualTo(1);
  }

  @Test void aRowThatItemNamesStillNeedCannotBeSwitchedOffOrMovedToAnotherCode() {
    Map<String, Object> salt = row("/api/v1/gst/hsn/2501");
    String id = (String) salt.get("rowId");
    ResponseEntity<Map> off = delete(id);
    assertThat(off.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(off.getBody().get("code")).isEqualTo("GST_HSN_IN_USE");
    Map<String, Object> move = body(salt, 0);
    move.put("hsn", "2502");
    ResponseEntity<Map> moved = put(id, move);
    assertThat(moved.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(moved.getBody().get("code")).isEqualTo("GST_HSN_IN_USE");
    assertThat(row("/api/v1/gst/hsn/2501").get("active")).isEqualTo(true);
    assertThat(jdbc.queryForObject("select count(*) from gst_master_audit where entity_id = ?::uuid", Long.class, id)).isZero();
  }

  @Test void anUnknownIdIs404ForPutAndDelete() {
    String id = UUID.randomUUID().toString();
    assertThat(put(id, Map.of("hsn", "7777", "description", "x", "gstPercent", 5, "taxType", "TAXABLE")).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(delete(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test void anEditMadeByAnotherServerIsPickedUpByTheNextCheck() {
    Map<String, Object> shampoo = best("shampoo");
    String id = (String) shampoo.get("rowId");
    jdbc.update("update gst_hsn_rate set gst_percent = 12, cgst_percent = 6, sgst_percent = 6, igst_percent = 12, updated_at = now() where id = ?::uuid", id);
    assertThat(((Number) best("shampoo").get("gstPercent")).intValue()).as("this server has not looked yet").isEqualTo(5);
    gst.reloadIfChanged();
    assertThat(((Number) best("shampoo").get("gstPercent")).intValue()).isEqualTo(12);
    jdbc.update("update gst_hsn_rate set gst_percent = 5, cgst_percent = 2.5, sgst_percent = 2.5, igst_percent = 5, updated_at = now() where id = ?::uuid", id);
    gst.reloadIfChanged();
    assertThat(((Number) best("shampoo").get("gstPercent")).intValue()).isEqualTo(5);
  }
}
