package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The GST and HSN master against real PostgreSQL and the delivered seed: the migrations, the 22 named suggestions of the acceptance
 * plan, response shapes, the read endpoints with ETag and paging, and who may call what. Read-only: edits are in {@link GstAdminIT}.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class GstMasterIT extends IntegrationTestBase {
  private String admin;
  private String cashier;

  @BeforeEach
  void tokens() {
    admin = adminToken();
    cashier = token("cashier@vijaysupermall.local");
  }

  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

  private Map<String, Object> suggest(String query) {
    ResponseEntity<Map> res = get("/api/v1/gst/suggest?" + query, admin);
    assertThat(res.getStatusCode()).as(query + " -> " + res.getBody()).isEqualTo(HttpStatus.OK);
    return res.getBody();
  }

  private Map<String, Object> byName(String name, String extra) { return suggest("name=" + enc(name) + (extra.isEmpty() ? "" : "&" + extra)); }

  private static long n(Object o) { return ((Number) o).longValue(); }

  // --- migrations V122 / V123 ---------------------------------------------------------------------------------------

  @Test void theMigrationsLoadTheDeliveredCountsWithoutOrphanKeywords() {
    assertThat(jdbc.queryForObject("select count(*) from gst_hsn_rate where row_no <= 564", Long.class)).isEqualTo(564);
    assertThat(jdbc.queryForObject("select count(*) from gst_keyword where row_no <= 5950", Long.class)).isEqualTo(5950);
    assertThat(jdbc.queryForObject("select count(*) from gst_rate_card", Long.class)).isEqualTo(12);
    assertThat(jdbc.queryForObject("select count(*) from gst_keyword k where not exists (select 1 from gst_hsn_rate h where h.hsn = k.hsn)", Long.class))
        .isZero();
    Map<String, Long> byRate = new TreeMap<>();
    jdbc.query("select gst_percent::int as p, count(*) as c from gst_hsn_rate where row_no <= 564 group by 1", rs -> { byRate.put(rs.getString("p"), rs.getLong("c")); });
    assertThat(byRate).containsExactlyInAnyOrderEntriesOf(Map.of("0", 103L, "3", 1L, "5", 247L, "18", 204L, "40", 9L));
    assertThat(jdbc.queryForObject("select count(*) from gst_hsn_rate where hsn = '8306' and condition_key = 'ANY'", Long.class))
        .as("both 8306 rows stay: the rate conflict is the owner's and the CA's decision").isEqualTo(2);
  }

  // --- the 22 named cases of TEST_PLAN section 1 -------------------------------------------------------------------

  static Stream<Arguments> namedCases() {
    return Stream.of(
        Arguments.of("1.1", "name=basmati rice 5 kg", "1006", "PACKED_LABELLED", "5", "LOOSE"),
        Arguments.of("1.2", "name=basmati rice&packed=false", "1006", "LOOSE", "0", "PACKED_LABELLED"),
        Arguments.of("1.3", "name=loose basmati rice", "1006", "LOOSE", "0", "PACKED_LABELLED"),
        Arguments.of("1.4", "name=chakki atta", "1101", "PACKED_LABELLED", "5", "LOOSE"),
        Arguments.of("1.5", "name=tur daal&packed=true", "0713", "PACKED_LABELLED", "5", "LOOSE"),
        Arguments.of("1.6", "name=haldi powder", "0910", "DRIED", "5", ""),
        Arguments.of("1.7", "name=cow ghee 1 litre", "0405", "ANY", "5", ""),
        Arguments.of("1.8", "name=uht milk", "0401", "ANY", "0", ""),
        Arguments.of("1.9", "name=paneer", "0406", "PANEER", "0", ""),
        Arguments.of("1.10", "name=amul butter", "0405", "ANY", "5", ""),
        Arguments.of("1.11", "name=parle g biscuits", "1905", "ANY", "5", ""),
        Arguments.of("1.12", "name=dairy milk chocolate", "1806", "ANY", "5", ""),
        Arguments.of("1.13", "name=shampoo", "3305", "ANY", "5", ""),
        Arguments.of("1.14", "name=t shirt&price=499", "6109", "PRICE_UPTO_2500", "5", "PRICE_ABOVE_2500"),
        Arguments.of("1.15", "name=t shirt&price=3000", "6109", "PRICE_ABOVE_2500", "18", "PRICE_UPTO_2500"),
        Arguments.of("1.16a", "name=t shirt&price=2500", "6109", "PRICE_UPTO_2500", "5", "PRICE_ABOVE_2500"),
        Arguments.of("1.16b", "name=t shirt&price=2500.01", "6109", "PRICE_ABOVE_2500", "18", "PRICE_UPTO_2500"),
        Arguments.of("1.17", "name=plain salt", "2501", "ANY", "0", ""),
        Arguments.of("1.18", "name=loose potato", "0701", "ANY", "0", ""),
        Arguments.of("1.19", "name=basmatti raice", "1006", "PACKED_LABELLED", "5", "LOOSE"),
        Arguments.of("1.20", "name=zeera", "0909", "ANY", "5", ""),
        Arguments.of("1.21a", "name=biskut", "1905", "ANY", "5", ""),
        Arguments.of("1.21b", "name=doodh", "0401", "ANY", "0", ""),
        Arguments.of("1.21c", "name=shakkar", "1701", "ANY", "5", ""),
        Arguments.of("1.22", "name=coca cola", "220210", "ANY", "40", ""));
  }

  @ParameterizedTest(name = "{0}: {1} -> {2} {3} {4}%")
  @MethodSource("namedCases")
  void theNamedSuggestionsGiveTheListedHsnConditionRateAndChoices(String id, String query, String hsn, String condition, String rate, String choices) {
    String q = Arrays.stream(query.split("&")).map(p -> p.substring(0, p.indexOf('=') + 1) + enc(p.substring(p.indexOf('=') + 1)))
        .collect(Collectors.joining("&"));
    Map<String, Object> body = suggest(q);
    Map<String, Object> best = (Map<String, Object>) body.get("best");
    assertThat(best).as(id).isNotNull();
    assertThat(best.get("hsn")).isEqualTo(hsn);
    assertThat(best.get("condition")).isEqualTo(condition);
    assertThat(new BigDecimal(best.get("gstPercent").toString())).isEqualByComparingTo(rate);
    List<Map<String, Object>> ch = (List<Map<String, Object>>) body.get("choices");
    assertThat(ch.stream().map(c -> (String) c.get("condition")).toList()).isEqualTo(choices.isEmpty() ? List.of() : List.of(choices));
    assertThat(ch).allMatch(c -> !c.get("rowId").equals(best.get("rowId")));
    assertThat(best.get("acceptedForBilling")).isEqualTo(true);
    double score = ((Number) best.get("score")).doubleValue();
    assertThat(score).isBetween(0.5, 1.0);
    assertThat((String) best.get("matchedName")).isNotBlank();
    assertThat((String) best.get("description")).isNotBlank();
    assertThat((String) best.get("verification")).isIn("VERIFIED", "VERIFY_PRODUCT");
    assertThat((String) best.get("effectiveFrom")).matches("\\d{4}-\\d{2}-\\d{2}");
    assertThat((String) best.get("taxType")).isIn("TAXABLE", "NIL");
  }

  @Test void ratesPrintAsPlainJsonNumbersInTheRawBody() {
    String raw = client().get().uri("/api/v1/gst/suggest?name=" + enc("basmati rice 5 kg")).headers(h -> h.setBearerAuth(admin)).retrieve().body(String.class);
    assertThat(raw).contains("\"gstPercent\":5,").contains("\"cgstRate\":2.5,").contains("\"cessRate\":0,").doesNotContain("5.000").doesNotContain("E0");
    String rates = client().get().uri("/api/v1/gst/rates").headers(h -> h.setBearerAuth(admin)).retrieve().body(String.class);
    assertThat(rates).contains("\"gstPercent\":0.25").contains("\"cgstRate\":0.125").contains("\"gstPercent\":1.5").doesNotContain("0.250");
  }

  @Test void junkAndPackSizesGiveNullBestAndEmptyLists() {
    for (String junk : List.of("xyzzy qwerty", "500 gm")) {
      Map<String, Object> body = byName(junk, "");
      assertThat(body.get("best")).isNull();
      assertThat((List) body.get("alternatives")).isEmpty();
      assertThat((List) body.get("choices")).isEmpty();
    }
  }

  @Test void alternativesAreOtherCodesAndLimitedAndAQuoteOrEmojiNeverBreaksIt() {
    Map<String, Object> body = byName("milk", "limit=3");
    Map<String, Object> best = (Map<String, Object>) body.get("best");
    List<Map<String, Object>> alts = (List<Map<String, Object>>) body.get("alternatives");
    assertThat(alts.size()).isLessThanOrEqualTo(2);
    assertThat(alts).allMatch(a -> !a.get("hsn").equals(best.get("hsn")) && ((Number) a.get("score")).doubleValue() >= 0.5);
    assertThat(alts.stream().map(a -> a.get("hsn")).distinct().count()).isEqualTo(alts.size());
    assertThat(get("/api/v1/gst/suggest?name=" + enc("o'neil 😀"), admin).getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> chicken = (Map<String, Object>) byName("chicken", "").get("best");
    assertThat(chicken.get("taxType")).isIn("NIL", "TAXABLE");
  }

  @Test void badSuggestParametersAre400NamingTheField() {
    assertThat(get("/api/v1/gst/suggest", admin).getBody()).extracting("code").isEqualTo("VALIDATION_FAILED");
    assertThat(((Map) ((Map) get("/api/v1/gst/suggest", admin).getBody().get("details")).get("fields"))).containsKey("name");
    for (Map.Entry<String, String> bad : Map.of("name=", "name", "name=" + "x".repeat(121), "name", "name=rice&limit=0", "limit", "name=rice&limit=11", "limit",
        "name=rice&price=-1", "price", "name=rice&price=20000000", "price", "name=rice&packed=maybe", "packed").entrySet()) {
      ResponseEntity<Map> res = get("/api/v1/gst/suggest?" + bad.getKey(), admin);
      assertThat(res.getStatusCode()).as(bad.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
      assertThat(((Map) ((Map) res.getBody().get("details")).get("fields"))).containsKey(bad.getValue());
    }
  }

  // --- list, one code, rate card ---------------------------------------------------------------------------------

  @Test void theListIsExactPagedFilteredAndSearchable() {
    Map<String, Object> first = get("/api/v1/gst/hsn", admin).getBody();
    assertThat(n(first.get("total"))).isEqualTo(jdbc.queryForObject("select count(*) from gst_hsn_rate", Long.class));
    assertThat(first.get("totalExact")).isEqualTo(true);
    assertThat(n(first.get("size"))).isEqualTo(20);
    assertThat((List) first.get("items")).hasSize(20);
    assertThat(first.get("hasMore")).isEqualTo(true);
    Map<String, Object> row = ((List<Map<String, Object>>) first.get("items")).get(0);
    assertThat(row).containsKeys("rowId", "chapter", "tariffDescription", "productDescription", "classificationConditions", "schedule",
        "notificationReference", "active", "updatedAt", "matchedName", "score", "acceptedForBilling");
    assertThat(row.get("matchedName")).isNull();
    assertThat(row.get("score")).isNull();
    assertThat(n(get("/api/v1/gst/hsn?size=50&page=11", admin).getBody().get("size"))).isEqualTo(50);
    assertThat(((List<Map<String, Object>>) get("/api/v1/gst/hsn?q=1006", admin).getBody().get("items")).stream().allMatch(r -> ((String) r.get("hsn")).startsWith("1006"))).isTrue();
    assertThat(((List<Map<String, Object>>) get("/api/v1/gst/hsn?q=basmati", admin).getBody().get("items")).stream().anyMatch(r -> r.get("hsn").equals("1006"))).isTrue();
    long bad = n(get("/api/v1/gst/hsn?verification=VERIFY_PRODUCT&size=1", admin).getBody().get("total"));
    long ok = n(get("/api/v1/gst/hsn?verification=VERIFIED&size=1", admin).getBody().get("total"));
    assertThat(bad + ok).isEqualTo(n(first.get("total")));
    assertThat(n(get("/api/v1/gst/hsn?taxType=NIL&size=1", admin).getBody().get("total"))).isEqualTo(jdbc.queryForObject("select count(*) from gst_hsn_rate where tax_type = 'NIL'", Long.class));
    for (String badQuery : List.of("size=51", "size=0", "page=-1", "verification=MAYBE", "taxType=ZERO")) {
      assertThat(get("/api/v1/gst/hsn?" + badQuery, admin).getStatusCode()).as(badQuery).isEqualTo(HttpStatus.BAD_REQUEST);
    }
  }

  @Test void oneCodeShowsItsConditionsAndPriceBandsAndTheRightErrors() {
    List<Map<String, Object>> rice = (List<Map<String, Object>>) get("/api/v1/gst/hsn/1006", admin).getBody().get("items");
    assertThat(rice).extracting(r -> r.get("condition")).containsExactlyInAnyOrder("LOOSE", "PACKED_LABELLED");
    List<Map<String, Object>> shirt = (List<Map<String, Object>>) get("/api/v1/gst/hsn/6109", admin).getBody().get("items");
    assertThat(shirt).hasSize(2);
    Map<String, Object> upto = shirt.stream().filter(r -> r.get("condition").equals("PRICE_UPTO_2500")).findFirst().orElseThrow();
    Map<String, Object> above = shirt.stream().filter(r -> r.get("condition").equals("PRICE_ABOVE_2500")).findFirst().orElseThrow();
    assertThat(n(upto.get("priceMax"))).isEqualTo(2500);
    assertThat(upto.get("priceMin")).isNull();
    assertThat(n(above.get("priceMin"))).isEqualTo(2500);
    assertThat(((List) get("/api/v1/gst/hsn/0401", admin).getBody().get("items"))).hasSize(3);
    assertThat(get("/api/v1/gst/hsn/9999", admin).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get("/api/v1/gst/hsn/9999", admin).getBody().get("code")).isEqualTo("NOT_FOUND");
    assertThat(get("/api/v1/gst/hsn/12", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test void theRateCardListsElevenFixedRatesWithTheBillingFlag() {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/gst/rates", admin).getBody().get("items");
    assertThat(items).hasSize(11);
    assertThat(items).noneMatch(r -> r.get("code").equals("GST_SPECIAL_CESS"));
    assertThat(items).allMatch(r -> r.get("acceptedForBilling").equals(true));
    assertThat(items).filteredOn(r -> List.of("GST_0_25", "GST_1_5", "GST_40").contains(r.get("code"))).hasSize(3)
        .allMatch(r -> !String.valueOf(r.get("note")).contains("Not accepted"));
  }

  @Test void theThreeReadEndpointsHaveAnEtagAndAnswer304ButTheSuggestHasNone() {
    for (String path : List.of("/api/v1/gst/rates", "/api/v1/gst/hsn", "/api/v1/gst/hsn?q=basmati", "/api/v1/gst/hsn/1006")) {
      ResponseEntity<String> first = client().get().uri(path).headers(h -> h.setBearerAuth(admin)).retrieve().toEntity(String.class);
      String etag = first.getHeaders().getETag();
      assertThat(etag).as(path).isNotBlank();
      ResponseEntity<String> again = client().get().uri(path).headers(h -> { h.setBearerAuth(admin); h.set(HttpHeaders.IF_NONE_MATCH, etag); }).retrieve().toEntity(String.class);
      assertThat(again.getStatusCode()).as(path).isEqualTo(HttpStatus.NOT_MODIFIED);
      assertThat(again.getBody()).isNull();
    }
    ResponseEntity<String> suggest = client().get().uri("/api/v1/gst/suggest?name=rice").headers(h -> h.setBearerAuth(admin)).retrieve().toEntity(String.class);
    assertThat(suggest.getHeaders().getETag()).isNull();
  }

  // --- who may call what --------------------------------------------------------------------------------------------

  @Test void adminAndCashierMayReadACustomerGets403AndNoTokenGets401() {
    String customer = token(freshEmail());
    for (String path : List.of("/api/v1/gst/suggest?name=rice", "/api/v1/gst/hsn", "/api/v1/gst/hsn/1006", "/api/v1/gst/rates")) {
      assertThat(get(path, admin).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
      assertThat(get(path, cashier).getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
      assertThat(get(path, customer).getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(get(path, null).getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
  }

  @Test void onlyTheAdminMayEditTheMaster() {
    String customer = token(freshEmail());
    String id = "/api/v1/admin/gst-master/rows/" + UUID.randomUUID();
    Map<String, Object> body = Map.of("hsn", "7777", "description", "x", "gstPercent", 5, "taxType", "TAXABLE");
    for (String token : Arrays.asList(cashier, customer)) {
      assertThat(post("/api/v1/admin/gst-master/rows", token, body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(call(HttpMethod.PUT, id, token, body, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
      assertThat(call(HttpMethod.DELETE, id, token, null, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
    assertThat(post("/api/v1/admin/gst-master/rows", null, body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(HttpMethod.PUT, id, null, body, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(call(HttpMethod.DELETE, id, null, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
