package com.vijay.mall.support;

import com.vijay.mall.MallApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

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

  @org.springframework.boot.test.web.server.LocalManagementPort protected int managementPort;

  @org.springframework.beans.factory.annotation.Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbc;

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
    var raw = (body == null ? spec : spec.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body))
        .retrieve().toEntity(String.class);
    return new ResponseEntity<>(parse(raw.getBody()), raw.getHeaders(), raw.getStatusCode());
  }

  private static final tools.jackson.databind.json.JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();

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
    List<Map<String, Object>> items = (List<Map<String, Object>>) get("/api/v1/products?size=50&includeOutOfStock=true", token).getBody().get("items");
    return items.stream().filter(p -> nameEn.equals(p.get("nameEn"))).findFirst().orElseThrow();
  }

  /** GET that returns a JSON array of objects. */
  @SuppressWarnings({"unchecked", "rawtypes"})
  protected List<Map<String, Object>> getList(String url, String token) {
    return (List) client().get().uri(url).headers(h -> { if (token != null) h.setBearerAuth(token); })
        .retrieve().body(List.class);
  }

  /** Adds stock to a seeded product so one test cannot starve another that runs later against the shared database. */
  @SuppressWarnings("rawtypes")
  protected void restock(String productName, int quantity) {
    String admin = adminToken();
    Object id = productByName(admin, productName).get("id");
    post("/api/v1/admin/products/" + id + "/stock", admin, Map.of("delta", quantity, "reason", "RESTOCK"));
  }
}
