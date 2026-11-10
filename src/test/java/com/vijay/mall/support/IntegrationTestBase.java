package com.vijay.mall.support;

import com.vijay.mall.MallApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared set-up for integration tests: one real PostgreSQL for the whole test run (needs Docker), the dev profile
 * (fixed OTP 12345 and sample shop data) and a small HTTP helper that never throws on 4xx/5xx.
 */
@ActiveProfiles("dev")
@SpringBootTest(classes = MallApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {
  static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");

  static {
    PG.start(); // stopped by Testcontainers' Ryuk when the JVM exits
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
    r.add("app.rate-limit.otp-per-ip-hour", () -> 100000); // every test logs in from the same address
    r.add("app.rate-limit.api-per-minute", () -> 100000);
    r.add("app.rate-limit.otp-per-email-hour", () -> 100000); // the admin signs in again in nearly every test
    r.add("app.rate-limit.otp-per-mobile-hour", () -> 100000);
    r.add("management.server.port", () -> 0); // metrics and health on a random private port, so several test contexts can coexist
  }

  @LocalServerPort int port;

  @LocalManagementPort protected int managementPort;

  @Autowired protected JdbcTemplate jdbc;

  private RestClient client;

  protected RestClient client() {
    if (client == null) {
      client = RestClient.builder().baseUrl("http://localhost:" + port)
          .defaultStatusHandler(_ -> true, (_, _) -> { /* tests assert on the status themselves */ })
          .build();
    }
    return client;
  }

  /** One HTTP call. {@code token} and {@code idempotencyKey} may be null. */
  @SuppressWarnings("rawtypes")
  protected ResponseEntity<Map> call(HttpMethod m, String url, String token, Object body, String idempotencyKey) {
    var spec = client().method(m).uri(url).headers((HttpHeaders h) -> {
      if (token != null) h.setBearerAuth(token);
      if (idempotencyKey != null) h.set("Idempotency-Key", idempotencyKey);
    });
    var raw = (body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body))
        .retrieve().toEntity(String.class);
    return new ResponseEntity<>(parse(raw.getBody()), raw.getHeaders(), raw.getStatusCode());
  }

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** A JSON object becomes a Map, a JSON array becomes {"_array": [...]} (so status checks work on list endpoints), anything else null. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Map parse(String text) {
    if (text == null || text.isBlank()) return null;
    try {
      var node = MAPPER.readTree(text);
      if (node.isArray()) return Map.of("_array", MAPPER.convertValue(node, List.class));
      return node.isObject() ? MAPPER.convertValue(node, Map.class) : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  @SuppressWarnings("rawtypes")
  protected ResponseEntity<Map> get(String url, String token) { return call(HttpMethod.GET, url, token, null, null); }

  @SuppressWarnings("rawtypes")
  protected ResponseEntity<Map> post(String url, String token, Object body) { return call(HttpMethod.POST, url, token, body, null); }

  /** Signs in with the dev OTP and returns the whole auth response (accessToken, refreshToken, user). */
  @SuppressWarnings("unchecked")
  protected Map<String, Object> login(String email) {
    post("/api/v1/auth/otp/send", null, Map.of("email", email));
    var res = post("/api/v1/auth/otp/verify", null,
        Map.of("email", email, "otp", "12345", "deviceId", UUID.randomUUID().toString()));
    if (!res.getStatusCode().is2xxSuccessful()) throw new AssertionError("login failed: " + res.getBody());
    return res.getBody();
  }

  protected String token(String email) { return (String) login(email).get("accessToken"); }

  protected String adminToken() { return token("admin@vijaysupermall.local"); }

  protected String freshEmail() { return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com"; }

  @SuppressWarnings("unchecked")
  protected Map<String, Object> productByName(String token, String nameEn) {
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true&q=" + java.net.URLEncoder.encode(nameEn, java.nio.charset.StandardCharsets.UTF_8), token).getBody().get("items");
    return items.stream().filter(p -> nameEn.equals(p.get("nameEn"))).findFirst().orElseThrow();
  }

  /** Switches one payment method on or off, sending the full list the endpoint now requires (the other method keeps its state). */
  @SuppressWarnings("rawtypes")
  protected ResponseEntity<Map> setPaymentMethod(String adminToken, String code, boolean enabled) {
    List<Map<String, Object>> all = new java.util.ArrayList<>();
    for (Map<String, Object> m : getList("/api/v1/admin/payment-methods", adminToken)) {
      all.add(Map.of("code", m.get("code"), "enabled", code.equals(m.get("code")) ? enabled : m.get("enabled")));
    }
    return call(HttpMethod.PUT, "/api/v1/admin/payment-methods", adminToken, all, null);
  }

  /**
   * Only one coin rule can be active, so a test that switches rules leaves the sample data's rule off or deleted. Puts the dev sample
   * rule back as the one active rule (through the API, so the rule cache is emptied too) for the tests that run after it.
   */
  @AfterEach
  protected void restoreSampleCoinRule() {
    List<Map<String, Object>> rule = jdbc.queryForList(
        "select id::text id, spend_paise, coins, mode, min_order_paise, active from coin_rule where name = 'Spend 1000 get 10'");
    boolean othersActive = jdbc.queryForObject("select count(*) from coin_rule where active and name <> 'Spend 1000 get 10'", Long.class) > 0;
    if (rule.isEmpty() || (Boolean.TRUE.equals(rule.get(0).get("active")) && !othersActive)) return;
    Map<String, Object> r = rule.get(0);
    call(HttpMethod.PUT, "/api/v1/admin/coin-rules/" + r.get("id"), adminToken(), Map.of("name", "Spend 1000 get 10",
        "spendPaise", r.get("spend_paise"), "coins", r.get("coins"), "mode", r.get("mode"), "minOrderPaise", r.get("min_order_paise"), "active", true), null);
  }

  /** GET that returns a JSON array of objects. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  protected List<Map<String, Object>> getList(String url, String token) {
    return (List) client().get().uri(url).headers(h -> { if (token != null) h.setBearerAuth(token); })
        .retrieve().body(List.class);
  }

  /**
   * Adds stock to a seeded product so one test cannot starve another that runs later against the shared database. Every product with
   * that name gets it (a test may have added a second pack size, and the name alone does not say which one a test sells).
   */
  protected void restock(String productName, int quantity) {
    String admin = adminToken();
    for (String id : jdbc.queryForList("select id::text from product where name_en = ?", String.class, productName)) {
      post("/api/v1/admin/products/" + id + "/stock", admin, Map.of("delta", quantity, "reason", "RESTOCK"));
    }
  }
}
