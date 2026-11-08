import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load test for the shop's API, no installation needed:   java ops/perf/LoadTest.java <baseUrl> <scenario> <users> <seconds>
 *
 * Scenarios (each virtual user loops through it for the given time):
 *   browse    guest browsing: categories, product list, hot sale, product detail
 *   shopper   signed-in customer: browse, quote, place a cash-on-delivery order (stock is topped up first)
 *   counter   cashier: scan, quote a bill, ring up a cash bill
 *   admin     back office: dashboard, reports, order list and search, customer search, product search, low stock
 *   mixed     70% browse, 20% shopper, 10% counter
 *
 * Needs the server running with the dev profile (login code 12345), a high rate limit
 * (APP_RATE_LIMIT_API_PER_MINUTE=1000000, APP_RATE_LIMIT_OTP_PER_IP_HOUR=1000000, APP_RATE_LIMIT_OTP_PER_EMAIL_HOUR=1000000)
 * and a database you do not mind filling with test orders.
 */
public class LoadTest {
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  static String base;
  static final Map<String, List<Long>> times = new ConcurrentHashMap<>();
  static final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
  static final Map<String, AtomicInteger> statuses = new ConcurrentHashMap<>();
  static final AtomicLong total = new AtomicLong();

  record Res(int status, String body) {}

  static Res call(String name, String method, String path, String token, String key, String json) {
    var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30));
    if (token != null) b.header("Authorization", "Bearer " + token);
    if (key != null) b.header("Idempotency-Key", key);
    if (json != null) b.header("Content-Type", "application/json");
    b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
    long t0 = System.nanoTime();
    try {
      var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      long ms = (System.nanoTime() - t0) / 1_000_000;
      times.computeIfAbsent(name, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(ms);
      statuses.computeIfAbsent(name + " " + r.statusCode(), k -> new AtomicInteger()).incrementAndGet();
      if (r.statusCode() >= 400) errors.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
      total.incrementAndGet();
      return new Res(r.statusCode(), r.body());
    } catch (Exception e) {
      errors.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
      statuses.computeIfAbsent(name + " EXC", k -> new AtomicInteger()).incrementAndGet();
      return new Res(0, "");
    }
  }

  static String find(String body, String field) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }

  static String login(String email) {
    call("login send", "POST", "/api/v1/auth/otp/send", null, null, "{\"email\":\"" + email + "\"}");
    var r = call("login verify", "POST", "/api/v1/auth/otp/verify", null, null,
        "{\"email\":\"" + email + "\",\"otp\":\"12345\",\"deviceId\":\"" + UUID.randomUUID() + "\"}");
    return find(r.body(), "accessToken");
  }

  static List<String> productIds(String token) {
    var r = call("setup products", "GET", "/api/v1/products?size=50", token, null, null);
    List<String> ids = new ArrayList<>();
    Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(r.body());
    while (m.find()) if (!ids.contains(m.group(1))) ids.add(m.group(1));
    return ids;
  }

  static void browse(List<String> ids, int n) {
    call("GET /categories", "GET", "/api/v1/categories", null, null, null);
    call("GET /products", "GET", "/api/v1/products?size=20", null, null, null);
    call("GET /hot-sale", "GET", "/api/v1/hot-sale", null, null, null);
    call("GET /products/{id}", "GET", "/api/v1/products/" + ids.get(n % ids.size()), null, null, null);
  }

  static void shopper(String token, List<String> ids, int n) {
    browse(ids, n);
    String items = "[{\"productId\":\"" + ids.get(n % ids.size()) + "\",\"qty\":1}]";
    String cart = "{\"items\":" + items + ",\"coinMode\":\"NONE\",\"fulfilment\":\"PICKUP\"}";
    var q = call("POST /cart/quote", "POST", "/api/v1/cart/quote", token, null, cart);
    Matcher m = Pattern.compile("\"totalPaise\"\\s*:\\s*(\\d+)").matcher(q.body());
    if (q.status() == 200 && m.find()) {
      call("POST /orders", "POST", "/api/v1/orders", token, UUID.randomUUID().toString(),
          "{\"items\":" + items + ",\"coinMode\":\"NONE\",\"fulfilment\":\"PICKUP\",\"paymentMethod\":\"COD\",\"expectedTotalPaise\":" + m.group(1) + "}");
      call("GET /orders", "GET", "/api/v1/orders?size=10", token, null, null);
    }
  }

  static void counter(String token, List<String> ids, int n) {
    String cart = "{\"items\":[{\"productId\":\"" + ids.get(n % ids.size()) + "\",\"qty\":1}]}";
    call("GET /pos/products", "GET", "/api/v1/pos/products?q=Rice&size=10", token, null, null);
    var q = call("POST /pos/quote", "POST", "/api/v1/pos/quote", token, null, cart);
    Matcher m = Pattern.compile("\"totalPaise\"\\s*:\\s*(\\d+)").matcher(q.body());
    if (q.status() == 200 && m.find()) {
      call("POST /pos/sales", "POST", "/api/v1/pos/sales", token, UUID.randomUUID().toString(),
          "{\"cart\":" + cart + ",\"payments\":[{\"mode\":\"CASH\",\"amountPaise\":" + m.group(1) + "}]}");
    }
  }

  static void admin(String token, int n) {
    call("ADMIN dashboard", "GET", "/api/v1/admin/dashboard", token, null, null);
    call("ADMIN reports/summary", "GET", "/api/v1/admin/reports/summary?period=month", token, null, null);
    call("ADMIN orders list", "GET", "/api/v1/admin/orders?page=" + (n % 50) + "&size=20", token, null, null);
    call("ADMIN orders search", "GET", "/api/v1/admin/orders?q=BULK-" + (1000 + n % 9000), token, null, null);
    call("ADMIN customers search", "GET", "/api/v1/admin/customers?q=bulk" + (100 + n % 900), token, null, null);
    call("ADMIN products search", "GET", "/api/v1/admin/products?q=Item%20" + (100 + n % 900) + "&size=20", token, null, null);
    call("ADMIN low stock", "GET", "/api/v1/admin/stock/low?limit=50", token, null, null);
    call("ADMIN counter controls", "GET", "/api/v1/admin/reports/counter-controls?period=month", token, null, null);
  }

  public static void main(String[] a) throws Exception {
    base = a.length > 0 ? a[0] : "http://localhost:8080";
    String scenario = a.length > 1 ? a[1] : "mixed";
    int users = a.length > 2 ? Integer.parseInt(a[2]) : 20;
    int seconds = a.length > 3 ? Integer.parseInt(a[3]) : 30;

    String admin = login("admin@vijaysupermall.local");
    String cashier = login("cashier@vijaysupermall.local");
    if (admin == null || cashier == null) { System.err.println("Could not sign in: is the server running with the dev profile?"); System.exit(2); }
    List<String> ids = productIds(admin);
    // plenty of stock so the test measures speed, not "sold out"
    for (String id : ids) call("setup stock", "POST", "/api/v1/admin/products/" + id + "/stock", admin, null, "{\"delta\":200000,\"reason\":\"RESTOCK\"}");
    times.clear(); statuses.clear(); errors.clear(); total.set(0);

    List<String> customers = new ArrayList<>();
    for (int i = 0; i < Math.max(1, users / 2); i++) customers.add(login("load-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com"));
    times.clear(); statuses.clear(); errors.clear(); total.set(0);

    long end = System.nanoTime() + seconds * 1_000_000_000L;
    long t0 = System.nanoTime();
    var pool = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<?>> fs = new ArrayList<>();
    for (int u = 0; u < users; u++) {
      final int id = u;
      fs.add(pool.submit(() -> {
        String token = customers.get(id % customers.size());
        int n = id;
        while (System.nanoTime() < end) {
          n++;
          int dice = (n * 31 + id) % 10;
          switch (scenario) {
            case "browse" -> browse(ids, n);
            case "shopper" -> shopper(token, ids, n);
            case "counter" -> counter(cashier, ids, n);
            case "admin" -> admin(admin, n);
            default -> { if (dice < 7) browse(ids, n); else if (dice < 9) shopper(token, ids, n); else counter(cashier, ids, n); }
          }
        }
      }));
    }
    for (var f : fs) f.get();
    double secs = (System.nanoTime() - t0) / 1e9;
    pool.shutdown();

    System.out.printf("%nScenario %s, %d virtual users, %.1f s, %d requests, %.1f req/s%n%n", scenario, users, secs, total.get(), total.get() / secs);
    System.out.printf("%-22s %7s %7s %7s %7s %7s %6s%n", "request", "count", "p50 ms", "p95 ms", "p99 ms", "max ms", "errors");
    var names = new ArrayList<>(times.keySet());
    java.util.Collections.sort(names);
    long all = 0; int bad = 0;
    for (String n : names) {
      long[] t = times.get(n).stream().mapToLong(Long::longValue).sorted().toArray();
      int e = errors.getOrDefault(n, new AtomicInteger()).get();
      all += t.length; bad += e;
      System.out.printf("%-22s %7d %7d %7d %7d %7d %6d%n", n, t.length, t[t.length / 2], t[(int) Math.min(t.length - 1, t.length * 95L / 100)],
          t[(int) Math.min(t.length - 1, t.length * 99L / 100)], t[t.length - 1], e);
    }
    System.out.printf("%nError rate: %.2f%% (%d of %d)%n", all == 0 ? 0 : 100.0 * bad / all, bad, all);
    var codes = new ArrayList<>(statuses.keySet());
    java.util.Collections.sort(codes);
    System.out.println("Status codes: " + codes.stream().map(k -> k + "=" + statuses.get(k)).toList());
  }
}
