import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/**
 * Per-endpoint benchmark: every HTTP endpoint is measured on its own, one at a time.
 *
 *   java ops/perf/EndpointBench.java <baseUrl> [filter[,filter...]]
 *
 * For each case: warm-up 4 users x 3 s (discarded), run A = 1 user x 5 s (uncontended latency),
 * run B = 16 closed-loop users x 10 s, no think time (throughput under load).
 * Cases that consume something (cancel, reject, delete, return, ...) draw a fresh target from a pool that is
 * prepared OUTSIDE the timing before each run; only the call under test is timed. If a pool runs dry the run ends
 * early and the case is flagged (TPS is then computed over the time actually spent).
 *
 * Needs: dev profile server (fixed OTP 12345), rate limits lifted, PostgreSQL in docker container "mallpg" (used to
 * pick sample ids). Results are merged into the TSV given by -Dout=... (default bench-results.tsv) by case id, so
 * a re-run with a filter only replaces the selected cases.  -Dwarm=3 -Da=5 -Db=10 -Dusers=16 change the method.
 */
public class EndpointBench {
  static String base;
  static final String OUT = System.getProperty("out", "bench-results.tsv");
  static final int WARM = Integer.getInteger("warm", 3), A_SEC = Integer.getInteger("a", 5), B_SEC = Integer.getInteger("b", 10), USERS = Integer.getInteger("users", 16);
  static final String DB = System.getProperty("dbcontainer", "mallpg");
  static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).executor(Executors.newCachedThreadPool()).build();
  static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  // ------------------------------------------------------------------ http
  record Res(int status, String body, long nanos) {
    double ms() { return nanos / 1e6; }
  }

  static class Rq {
    String m, p, tok, idem, ctype; byte[] body; boolean sse;
    static Rq of(String m, String p) { Rq r = new Rq(); r.m = m; r.p = p; return r; }
    static Rq get(String p) { return of("GET", p); }
    static Rq post(String p) { return of("POST", p); }
    static Rq put(String p) { return of("PUT", p); }
    static Rq del(String p) { return of("DELETE", p); }
    static Rq patch(String p) { return of("PATCH", p); }
    Rq as(String t) { tok = t; return this; }
    Rq json(String j) { body = j.getBytes(StandardCharsets.UTF_8); ctype = "application/json"; return this; }
    Rq idem() { idem = uuid(); return this; }
    Rq stream() { sse = true; return this; }
    Rq file(String field, String name, byte[] data) {
      String b = "----bench" + System.nanoTime();
      var o = new ByteArrayOutputStream();
      try {
        o.write(("--" + b + "\r\nContent-Disposition: form-data; name=\"" + field + "\"; filename=\"" + name + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(data);
        o.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
      } catch (Exception e) { throw new RuntimeException(e); }
      body = o.toByteArray(); ctype = "multipart/form-data; boundary=" + b; return this;
    }
  }

  static HttpRequest build(Rq r) {
    var b = HttpRequest.newBuilder(URI.create(base + r.p)).timeout(Duration.ofSeconds(30));
    if (r.tok != null) b.header("Authorization", "Bearer " + r.tok);
    if (r.idem != null) b.header("Idempotency-Key", r.idem);
    if (r.ctype != null) b.header("Content-Type", r.ctype);
    if (r.sse) b.header("Accept", "text/event-stream");
    b.method(r.m, r.body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(r.body));
    return b.build();
  }

  static Res send(Rq r) {
    long t0 = System.nanoTime();
    try {
      if (r.sse) {
        var resp = HTTP.send(build(r), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
          int first = resp.statusCode() == 200 ? in.read() : -1;   // time to first byte of the stream
          long ns = System.nanoTime() - t0;
          return new Res(resp.statusCode(), first < 0 ? "no bytes" : "", ns);
        }
      }
      var resp = HTTP.send(build(r), HttpResponse.BodyHandlers.ofByteArray());
      return new Res(resp.statusCode(), new String(resp.body(), StandardCharsets.UTF_8), System.nanoTime() - t0);
    } catch (Exception e) {
      String n = e.getClass().getSimpleName();
      return new Res(0, n + ": " + e.getMessage(), System.nanoTime() - t0);
    }
  }

  static Res call(Rq r) { return send(r); }
  static Res get(String p, String tok) { return send(Rq.get(p).as(tok)); }
  static Res post(String p, String tok, String json) { Rq r = Rq.post(p).as(tok); if (json != null) r.json(json); return send(r); }
  static Res postI(String p, String tok, String json) { Rq r = Rq.post(p).as(tok).idem(); if (json != null) r.json(json); return send(r); }
  static Res put(String p, String tok, String json) { return send(Rq.put(p).as(tok).json(json)); }

  static String uuid() { return UUID.randomUUID().toString(); }
  static String hex8() { return uuid().substring(0, 8); }
  static ThreadLocalRandom rnd() { return ThreadLocalRandom.current(); }

  static String str(String body, String field) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }
  static List<String> strs(String body, String field) {
    List<String> l = new ArrayList<>();
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
    while (m.find()) l.add(m.group(1));
    return l;
  }
  static long num(String body, String field, long dflt) {
    Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);
    return m.find() ? Long.parseLong(m.group(1)) : dflt;
  }

  static List<String> sql(String q) {
    try {
      Process p = new ProcessBuilder("docker", "exec", DB, "psql", "-U", "mall", "-d", "mall", "-Atc", q).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      p.waitFor();
      List<String> l = new ArrayList<>();
      for (String s : out.split("\n")) if (!s.isBlank()) l.add(s.trim());
      return l;
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  // ------------------------------------------------------------------ world (shared test data)
  static class Cust {
    String email, tok, refresh, userId, addr; long loginAt; final List<String> orders = new ArrayList<>();
    String tok() { return tok; }
  }

  static Cust loginCust(String email) {
    call(Rq.post("/api/v1/auth/otp/send").json("{\"email\":\"" + email + "\"}"));
    Res r = call(Rq.post("/api/v1/auth/otp/verify").json("{\"email\":\"" + email + "\",\"otp\":\"12345\",\"deviceId\":\"" + uuid() + "\"}"));
    if (r.status() != 200) throw new RuntimeException("login failed " + email + " " + r.status() + " " + r.body());
    Cust c = new Cust(); c.email = email; c.loginAt = System.currentTimeMillis();
    c.tok = str(r.body(), "accessToken"); c.refresh = str(r.body(), "refreshToken");
    Matcher m = Pattern.compile("\"user\"\\s*:\\s*\\{\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body());
    if (m.find()) c.userId = m.group(1);
    return c;
  }

  static volatile Cust admin, cashier;
  static final List<Cust> wc = new ArrayList<>();       // fresh write customers (own address and orders)
  static final List<Cust> rc = new ArrayList<>();       // seeded customers with a typical order history (read cases)
  static List<String> hp = new ArrayList<>();            // "hot" products: customer-visible, topped up stock
  static List<String> samplePids = new ArrayList<>(), cats = new ArrayList<>(), ordIds = new ArrayList<>(), custIds = new ArrayList<>(),
      saleIds = new ArrayList<>(), saleInv = new ArrayList<>(), barcodes = new ArrayList<>(), couponIds = new ArrayList<>(), groupIds = new ArrayList<>(),
      verifyEmails = new ArrayList<>(), flashPids = new ArrayList<>();
  static final AtomicInteger verifyIdx = new AtomicInteger(), flashIdx = new AtomicInteger();
  static final AtomicLong slot = new AtomicLong(), mobileSeq = new AtomicLong(System.nanoTime() % 100000000L);
  static final ConcurrentHashMap<String, Long> quoteCache = new ConcurrentHashMap<>(), posQuoteCache = new ConcurrentHashMap<>();
  static long authAt;

  static String adm() { return admin.tok; }
  static String csh() { return cashier.tok; }
  static Cust w(long n) { return wc.get((int) Math.floorMod(n, (long) wc.size())); }
  static Cust r(long n) { return rc.get((int) Math.floorMod(n, (long) rc.size())); }
  static String pick(List<String> l) { return l.get(rnd().nextInt(l.size())); }
  static String hotP() { return pick(hp); }

  static void relogin(Cust c) { Cust n = loginCust(c.email); c.tok = n.tok; c.refresh = n.refresh; c.loginAt = n.loginAt; }

  static synchronized void ensureAuth(boolean force) {
    if (!force && System.currentTimeMillis() - authAt < 6 * 60_000L) return;
    Cust a = loginCust("admin@vijaysupermall.local"), s = loginCust("cashier@vijaysupermall.local");
    admin = a; cashier = s;
    List<Cust> all = new ArrayList<>(wc); all.addAll(rc);
    all.parallelStream().forEach(EndpointBench::relogin);
    authAt = System.currentTimeMillis();
  }

  static boolean worldReady;

  static void setupWorld() throws Exception {
    System.out.println("== setup");
    admin = loginCust("admin@vijaysupermall.local"); cashier = loginCust("cashier@vijaysupermall.local");
    if (admin.tok == null) throw new IllegalStateException("cannot log in");
    authAt = System.currentTimeMillis();
    hp = sql("select id from product where active and not pos_only order by random() limit 60");
    samplePids = sql("select id from product where active and not pos_only order by random() limit 500");
    cats = sql("select id from category where name_en not like 'Bench%' and name_en not like 'Fixture%' order by random() limit 5");
    ordIds = sql("select id from orders order by random() limit 400");
    custIds = sql("select id from users where role='CUSTOMER' order by random() limit 400");
    saleIds = sql("select id from pos_sale order by random() limit 300");
    barcodes = sql("select code from product_barcode order by random() limit 300");
    couponIds = sql("select id from coupon limit 20");
    groupIds = sql("select product_group_id from product order by random() limit 300");
    List<String> rcEmails = sql("select u.email from users u join (select user_id from orders group by user_id having count(*) between 5 and 12 limit 40) o on o.user_id=u.id where u.email is not null limit 24");
    verifyEmails = sql("select email from users where role='CUSTOMER' and status='ACTIVE' and email is not null and email not in (" + String.join(",", rcEmails.stream().map(s -> "'" + s + "'").toList()) + ") order by random() limit 9000");
    flashPids = sql("select id from product where active and not pos_only and price_paise>=2000 order by random() limit 14000");
    System.out.println("products hp=" + hp.size() + " sample=" + samplePids.size() + " orders=" + ordIds.size() + " rc=" + rcEmails.size() + " verifyEmails=" + verifyEmails.size() + " flashPids=" + flashPids.size());
    // stock top-up so orders and bills never fail for stock
    List<String> topUp = new ArrayList<>(hp);
    sql("select 1").size();
    for (String id : topUp) post("/api/v1/admin/products/" + id + "/stock", adm(), "{\"delta\":2000000,\"reason\":\"RESTOCK\"}");
    // seeded read customers
    for (String e : rcEmails) rc.add(loginCust(e));
    for (Cust c : rc) {
      Res o = get("/api/v1/orders?size=1", c.tok);
      String id = str(o.body(), "id");
      if (id != null) c.orders.add(id);
    }
    rc.removeIf(c -> c.orders.isEmpty());
    // fresh write customers with an address and a couple of orders
    List<Cust> made = Collections.synchronizedList(new ArrayList<>());
    ExecutorService ex = Executors.newFixedThreadPool(8);
    List<Future<?>> fs = new ArrayList<>();
    for (int i = 0; i < 24; i++) fs.add(ex.submit(() -> {
      Cust c = loginCust("perf-" + uuid() + "@example.com");
      Res a = post("/api/v1/me/addresses", c.tok, "{\"label\":\"Home\",\"line1\":\"12 Rose Apt, Ring Road\",\"area\":\"Satellite\",\"pincode\":\"380015\",\"isDefault\":true}");
      c.addr = str(a.body(), "id");
      post("/api/v1/me", c.tok, null);
      made.add(c);
    }));
    for (var f : fs) f.get();
    ex.shutdown();
    wc.addAll(made);
    for (Cust c : wc) for (int k = 0; k < 3; k++) { String id = placeOrder(c, hotP(), 1); if (id != null) c.orders.add(id); }
    for (Cust c : wc) if (!c.orders.isEmpty()) post("/api/v1/admin/orders/" + c.orders.get(0) + "/status", adm(), "{\"status\":\"CONFIRMED\"}");
    worldReady = true;
    System.out.println("write customers=" + wc.size() + " read customers=" + rc.size());
  }

  // ------------------------------------------------------------------ business helpers
  static long quoteTotal(String pid, int qty) {
    return quoteCache.computeIfAbsent(pid + ":" + qty, k -> {
      Res q = post("/api/v1/cart/quote", wc.isEmpty() ? rc.get(0).tok : wc.get(0).tok,
          "{\"items\":[{\"productId\":\"" + pid + "\",\"qty\":" + qty + "}],\"coinMode\":\"NONE\",\"fulfilment\":\"DELIVERY\"}");
      return num(q.body(), "totalPaise", 0);
    });
  }

  static String orderBody(Cust c, String pid, int qty) {
    return "{\"items\":[{\"productId\":\"" + pid + "\",\"qty\":" + qty + "}],\"coinMode\":\"NONE\",\"fulfilment\":\"DELIVERY\",\"addressId\":\"" + c.addr
        + "\",\"paymentMethod\":\"COD\",\"expectedTotalPaise\":" + quoteTotal(pid, qty) + "}";
  }

  static Res placeOrderRes(Cust c, String pid, int qty) { return postI("/api/v1/orders", c.tok, orderBody(c, pid, qty)); }
  static String placeOrder(Cust c, String pid, int qty) {
    Res r = placeOrderRes(c, pid, qty);
    if (r.status() != 201) { System.out.println("  prep: order failed " + r.status() + " " + trunc(r.body(), 160)); return null; }
    return str(r.body(), "id");
  }
  static String deliveredOrder(Cust c, String pid, int qty) {
    String id = placeOrder(c, pid, qty);
    if (id == null) return null;
    Res s = post("/api/v1/admin/orders/" + id + "/status", adm(), "{\"status\":\"DELIVERED\"}");
    if (s.status() != 200) { System.out.println("  prep: deliver failed " + s.status() + " " + trunc(s.body(), 160)); return null; }
    return id;
  }

  static long posQuote(String pid, int qty) {
    return posQuoteCache.computeIfAbsent(pid + ":" + qty, k -> num(post("/api/v1/pos/quote", csh(), "{\"items\":[{\"productId\":\"" + pid + "\",\"qty\":" + qty + "}]}").body(), "totalPaise", 0));
  }
  static String cartJson(String pid, int qty) { return "{\"items\":[{\"productId\":\"" + pid + "\",\"qty\":" + qty + "}]}"; }
  static String saleBody(String pid, int qty) {
    return "{\"cart\":" + cartJson(pid, qty) + ",\"payments\":[{\"mode\":\"CASH\",\"amountPaise\":" + posQuote(pid, qty) + ",\"reference\":null}]}";
  }
  /** returns {saleId, firstItemId} or null */
  static String[] ringSale(String pid, int qty) {
    Res s = postI("/api/v1/pos/sales", csh(), saleBody(pid, qty));
    if (s.status() != 201) { System.out.println("  prep: sale failed " + s.status() + " " + trunc(s.body(), 160)); return null; }
    List<String> ids = strs(s.body(), "id");
    return new String[]{ids.get(0), ids.size() > 1 ? ids.get(1) : null, String.valueOf(qty)};
  }

  static String iso(Instant i) { return i.truncatedTo(ChronoUnit.SECONDS).toString(); }
  static String nextFlashPid() { return flashPids.get(Math.floorMod(flashIdx.getAndIncrement(), flashPids.size())); }
  /** distinct one-hour window per call, far enough apart that sales of the same product never overlap */
  static Instant[] slotWindow() { Instant s = Instant.now().plus(1, ChronoUnit.DAYS).plus(2L * slot.getAndIncrement(), ChronoUnit.HOURS); return new Instant[]{s, s.plus(1, ChronoUnit.HOURS)}; }
  static String flashJson(String name, Instant start, Instant end, String... pids) {
    StringBuilder it = new StringBuilder();
    for (String p : pids) { if (it.length() > 0) it.append(","); it.append("{\"productId\":\"").append(p).append("\",\"maxQty\":50}"); }
    return "{\"name\":\"" + name + "\",\"description\":\"bench\",\"bannerText\":\"Up to 20% off\"," + (start == null ? "" : "\"startsAt\":\"" + iso(start) + "\",")
        + "\"endsAt\":\"" + iso(end) + "\",\"channel\":\"BOTH\",\"discountKind\":\"PERCENT\",\"discountValue\":20,\"perCustomerLimit\":2,\"items\":[" + it + "]}";
  }
  /** creates a SCHEDULED sale in its own time slot; returns {saleId, itemId1, itemId2?} */
  static String[] newScheduledSale(int nItems) { return newScheduledSale(nItems, "Bench"); }
  static String[] newScheduledSale(int nItems, String pre) {
    Instant[] w = slotWindow();
    String[] pids = new String[nItems];
    for (int i = 0; i < nItems; i++) pids[i] = nextFlashPid();
    Res r = post("/api/v1/admin/flash-sales", adm(), flashJson(pre + " " + hex8(), w[0], w[1], pids));
    if (r.status() != 201) { System.out.println("  prep: flash create failed " + r.status() + " " + trunc(r.body(), 200)); return null; }
    List<String> ids = strs(r.body(), "id");
    return ids.toArray(new String[0]);
  }

  static byte[] png() {
    try {
      var img = new BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB);
      int c = rnd().nextInt(0xffffff);
      for (int x = 0; x < 96; x++) for (int y = 0; y < 96; y++) img.setRGB(x, y, (c ^ (x * 2654435 + y * 40503)) & 0xffffff);
      var o = new ByteArrayOutputStream(); ImageIO.write(img, "png", o); return o.toByteArray();
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  static String trunc(String s, int n) { s = s == null ? "" : s.replace('\n', ' ').replace('\t', ' '); return s.length() > n ? s.substring(0, n) : s; }
  static String randomMobile() { return "9" + String.format("%09d", mobileSeq.getAndIncrement() % 1_000_000_000L); }

  // lazily built fixtures for admin write cases
  static List<String[]> benchA, benchV;        // {productId, groupId, categoryId}
  static List<String> benchCat, benchBanner, benchUnit, benchCoupon, benchCoin, benchStaff, benchSaleScheduled, benchPos, creditIds, creditMobiles;
  static synchronized List<String[]> benchProducts(boolean variantsSet) {
    if (benchA == null) {
      benchA = new ArrayList<>(); benchV = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        Res r = post("/api/v1/admin/products", adm(), productJson(cats.get(0), "Fixture base " + hex8(), 5000, 4000));
        if (r.status() == 201) (i < 32 ? benchA : benchV).add(new String[]{str(r.body(), "id"), str(r.body(), "productGroupId"), cats.get(0)});
      }
    }
    return variantsSet ? benchV : benchA;
  }
  static String productJson(String cat, String name, long mrp, long price) {
    return "{\"categoryId\":\"" + cat + "\",\"nameEn\":\"" + name + "\",\"nameGu\":\"" + name + "\",\"description\":\"bench " + hex8() + "\",\"mrpPaise\":" + mrp + ",\"pricePaise\":" + price
        + ",\"unit\":\"pc\",\"packSize\":\"500 g\",\"active\":true,\"hotSale\":false,\"earnsCoins\":true,\"coinRedeemable\":true,\"qtyOnHand\":40,\"lowThreshold\":5}";
  }
  static synchronized void benchOthers() {
    if (benchCat != null) return;
    benchCat = new ArrayList<>(); benchBanner = new ArrayList<>(); benchUnit = new ArrayList<>(); benchCoupon = new ArrayList<>(); benchCoin = new ArrayList<>(); benchStaff = new ArrayList<>(); benchPos = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      benchCat.add(str(post("/api/v1/admin/categories", adm(), "{\"nameEn\":\"Fixture cat " + hex8() + "\",\"nameGu\":\"બેન્ચ\",\"sortOrder\":99,\"active\":false}").body(), "id"));
      benchBanner.add(str(post("/api/v1/admin/hot-banners", adm(), "{\"title\":\"Fixture " + hex8() + "\",\"subtitle\":\"s\",\"active\":false}").body(), "id"));
      String code = "b" + hex8();
      post("/api/v1/admin/units", adm(), "{\"code\":\"" + code + "\",\"nameEn\":\"Fixture\",\"nameGu\":\"\",\"soldBy\":\"UNIT\",\"sortOrder\":90,\"active\":false}");
      benchUnit.add(code);
      benchCoupon.add(str(post("/api/v1/admin/coupons", adm(), couponJson("FXC" + hex8().toUpperCase(), 5)).body(), "id"));
      benchCoin.add(str(post("/api/v1/admin/coin-rules", adm(), coinRuleJson(10000, "Fixture")).body(), "id"));
      Res st = post("/api/v1/admin/staff", adm(), "{\"email\":\"fxstaff-" + uuid() + "@example.com\",\"name\":\"Fixture Staff\",\"mobile\":\"\"}");
      benchStaff.add(str(st.body(), "id"));
    }
    benchCat.removeIf(Objects::isNull); benchBanner.removeIf(Objects::isNull); benchCoupon.removeIf(Objects::isNull); benchCoin.removeIf(Objects::isNull); benchStaff.removeIf(Objects::isNull);
  }
  static String couponJson(String code, int pct) {
    return "{\"code\":\"" + code + "\",\"kind\":\"PERCENT\",\"value\":" + pct + ",\"minOrderPaise\":20000,\"maxDiscountPaise\":5000,\"perUserLimit\":1,\"totalLimit\":500,\"startAt\":\""
        + iso(Instant.now().plus(365, ChronoUnit.DAYS)) + "\",\"endAt\":\"" + iso(Instant.now().plus(400, ChronoUnit.DAYS)) + "\",\"active\":true,\"worksWithCoins\":true,\"firstOrderOnly\":false}";
  }
  static String coinRuleJson(long spend) { return coinRuleJson(spend, "Bench"); }
  static String coinRuleJson(long spend, String pre) {
    return "{\"name\":\"" + pre + " " + hex8() + "\",\"spendPaise\":" + spend + ",\"coins\":2,\"mode\":\"PRO_RATA\",\"minOrderPaise\":0,\"active\":false}";
  }
  static synchronized void creditSetup() {
    if (creditIds != null) return;
    creditIds = new ArrayList<>(); creditMobiles = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      String mob = randomMobile();
      Res r = put("/api/v1/admin/credit/limit", adm(), "{\"mobile\":\"" + mob + "\",\"limitPaise\":10000000}");
      String cid = str(r.body(), "customerId");
      if (cid == null) { System.out.println("  credit setup: no customerId in " + trunc(r.body(), 200)); continue; }
      Res a = post("/api/v1/admin/credit/accounts/" + cid + "/adjust", adm(), "{\"amountPaise\":2000000,\"note\":\"bench opening balance\"}");
      if (a.status() != 200) System.out.println("  credit setup: adjust " + a.status() + " " + trunc(a.body(), 200));
      creditIds.add(cid); creditMobiles.add(mob);
    }
  }
  static String freshStaffToken() {
    String email = "benchcash-" + uuid() + "@example.com";
    Res s = post("/api/v1/admin/staff", adm(), "{\"email\":\"" + email + "\",\"name\":\"Bench Cashier\"}");
    if (s.status() != 201 && s.status() != 200) { System.out.println("  prep: staff " + s.status() + " " + trunc(s.body(), 160)); return null; }
    return loginCust(email).tok;
  }

  // ------------------------------------------------------------------ case model
  interface RB { Rq make(int vu, long n, Object target) throws Exception; }
  interface PR { Object get() throws Exception; }
  interface HK { void on(int vu, Object target, Res res); }

  static final List<C> CASES = new ArrayList<>();

  static class C {
    String group, method, path, who, note = "";
    Set<Integer> ok = new HashSet<>();
    PR prep; RB rq; HK hook; IntFunction<List<Object>> bulk; Runnable after;
    int poolA = 700, poolB = 3500, poolW = 80;
    final ConcurrentHashMap<Integer, Object> state = new ConcurrentHashMap<>();
    String id() { return method + " " + path + (note.startsWith("[") ? " " + note.substring(0, note.indexOf(']') + 1) : ""); }
    C prep(PR p) { prep = p; return this; }
    C rq(RB r) { rq = r; return this; }
    C hook(HK h) { hook = h; return this; }
    C bulk(IntFunction<List<Object>> b) { bulk = b; return this; }
    C after(Runnable r) { after = r; return this; }
    C pools(int a, int b) { poolA = a; poolB = b; return this; }
    C note(String n) { note = n; return this; }
    boolean pooled() { return prep != null || bulk != null; }
  }

  static C add(String group, String method, String path, String who, int... ok) {
    C c = new C(); c.group = group; c.method = method; c.path = path; c.who = who;
    for (int s : ok) c.ok.add(s);
    CASES.add(c);
    return c;
  }

  // ------------------------------------------------------------------ the cases
  static void defineCases() {
    final String P = "/api/v1";
    // ---------- auth
    add("Auth", "POST", "/api/v1/auth/otp/send", "Public", 204).rq((vu, n, t) -> Rq.post(P + "/auth/otp/send").json("{\"email\":\"" + r(n).email + "\"}"));
    add("Auth", "POST", "/api/v1/auth/otp/verify", "Public", 200).note("[existing user login; the code is sent in untimed prep]")
        .prep(() -> { String e = verifyEmails.get(verifyIdx.getAndIncrement() % verifyEmails.size()); call(Rq.post(P + "/auth/otp/send").json("{\"email\":\"" + e + "\"}")); return e; })
        .rq((vu, n, t) -> Rq.post(P + "/auth/otp/verify").json("{\"email\":\"" + t + "\",\"otp\":\"12345\",\"deviceId\":\"" + uuid() + "\"}"));
    add("Auth", "POST", "/api/v1/auth/otp/send-mobile", "Public", 204).rq((vu, n, t) -> Rq.post(P + "/auth/otp/send-mobile").json("{\"mobile\":\"" + randomMobile() + "\"}"));
    add("Auth", "POST", "/api/v1/auth/otp/verify-mobile", "Public", 200).note("[first login of a new mobile user; code sent in untimed prep]")
        .prep(() -> { String m = randomMobile(); call(Rq.post(P + "/auth/otp/send-mobile").json("{\"mobile\":\"" + m + "\"}")); return m; })
        .rq((vu, n, t) -> Rq.post(P + "/auth/otp/verify-mobile").json("{\"mobile\":\"" + t + "\",\"otp\":\"12345\",\"deviceId\":\"" + uuid() + "\"}"));
    add("Auth", "POST", "/api/v1/auth/refresh", "Public", 200).note("[each user rotates its own refresh-token chain]")
        .rq((vu, n, t) -> { Object rt = CURRENT.state.computeIfAbsent(vu, k -> loginCust(w(vu + 100).email).refresh); return Rq.post(P + "/auth/refresh").json("{\"refreshToken\":\"" + rt + "\"}"); })
        .hook((vu, t, res) -> { if (res.status() == 200) CURRENT.state.put(vu, str(res.body(), "refreshToken")); else CURRENT.state.remove(vu); });
    add("Auth", "POST", "/api/v1/auth/logout", "Public", 204).prep(() -> loginCust(w(rnd().nextInt(1000)).email).refresh)
        .rq((vu, n, t) -> Rq.post(P + "/auth/logout").json("{\"refreshToken\":\"" + t + "\"}"));

    // ---------- profile
    add("Profile", "GET", "/api/v1/me", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/me").as(w(vu).tok));
    add("Profile", "PUT", "/api/v1/me", "Customer", 200).rq((vu, n, t) -> Rq.put(P + "/me").as(w(vu).tok).json("{\"name\":\"Asha " + hex8() + "\"}"));
    add("Profile", "GET", "/api/v1/me/addresses", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/me/addresses").as(w(vu).tok));
    add("Profile", "POST", "/api/v1/me/addresses", "Customer", 201).rq((vu, n, t) -> Rq.post(P + "/me/addresses").as(w(vu).tok).json("{\"label\":\"Work\",\"line1\":\"" + hex8() + " Business Park\",\"area\":\"Vesu\",\"pincode\":\"395007\",\"isDefault\":false}"));
    add("Profile", "PUT", "/api/v1/me/addresses/{id}", "Customer", 200).rq((vu, n, t) -> Rq.put(P + "/me/addresses/" + w(vu).addr).as(w(vu).tok).json("{\"label\":\"Home\",\"line1\":\"" + hex8() + " Rose Apt\",\"area\":\"Satellite\",\"pincode\":\"380015\"}"));
    add("Profile", "DELETE", "/api/v1/me/addresses/{id}", "Customer", 204)
        .prep(() -> { Cust c = w(rnd().nextInt(1000)); Res a = post(P + "/me/addresses", c.tok, "{\"label\":\"Tmp\",\"line1\":\"" + hex8() + "\",\"area\":\"Adajan\",\"pincode\":\"395009\",\"isDefault\":false}"); return new Object[]{c, str(a.body(), "id")}; })
        .rq((vu, n, t) -> { Object[] o = (Object[]) t; return Rq.del(P + "/me/addresses/" + o[1]).as(((Cust) o[0]).tok); });
    add("Profile", "POST", "/api/v1/me/devices", "Customer", 204).rq((vu, n, t) -> Rq.post(P + "/me/devices").as(w(vu).tok).json("{\"fcmToken\":\"bench-" + uuid() + "\",\"platform\":\"android\"}"));
    add("Profile", "DELETE", "/api/v1/me", "Customer", 204).note("[deletes a fresh account created in untimed prep]").pools(300, 1500)
        .prep(() -> loginCust("perf-del-" + uuid() + "@example.com")).rq((vu, n, t) -> Rq.del(P + "/me").as(((Cust) t).tok));

    // ---------- notifications / wishlist / wallet / credit (customer)
    add("Notifications", "GET", "/api/v1/me/notifications", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/me/notifications?page=0&size=20").as(w(vu).tok));
    add("Notifications", "POST", "/api/v1/me/notifications/{id}/read", "Customer", 204).note("[idempotent; one notification id per user]")
        .rq((vu, n, t) -> { Object[] o = (Object[]) CURRENT.state.computeIfAbsent(vu, k -> {
          for (int i = 0; i < rc.size(); i++) { Cust c = r(vu + i); String x = str(get(P + "/me/notifications?size=5", c.tok).body(), "id"); if (x != null) return new Object[]{x, c}; }
          for (Cust c : wc) { String x = str(get(P + "/me/notifications?size=5", c.tok).body(), "id"); if (x != null) return new Object[]{x, c}; }
          return new Object[]{str(get(P + "/me/notifications?size=5", adm()).body(), "id"), admin}; });
          return Rq.post(P + "/me/notifications/" + o[0] + "/read").as(((Cust) o[1]).tok); });
    add("Notifications", "POST", "/api/v1/me/notifications/read-all", "Customer", 204).rq((vu, n, t) -> Rq.post(P + "/me/notifications/read-all").as(w(vu).tok));
    add("Wishlist", "GET", "/api/v1/me/wishlist", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/me/wishlist").as(w(vu).tok));
    add("Wishlist", "POST", "/api/v1/me/wishlist/{productId}", "Customer", 204).note("[cycles 150 products per user: first passes insert, later passes are no-ops]")
        .rq((vu, n, t) -> Rq.post(P + "/me/wishlist/" + samplePids.get((int) (n % 150))).as(w(vu).tok));
    add("Wishlist", "DELETE", "/api/v1/me/wishlist/{productId}", "Customer", 204).note("[product saved in untimed prep]")
        .prep(() -> { Cust c = w(rnd().nextInt(1000)); String p = pick(samplePids); post(P + "/me/wishlist/" + p, c.tok, null); return new Object[]{c, p}; })
        .rq((vu, n, t) -> { Object[] o = (Object[]) t; return Rq.del(P + "/me/wishlist/" + o[1]).as(((Cust) o[0]).tok); });
    add("Wallet", "GET", "/api/v1/wallet", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/wallet").as(r(vu).tok));
    add("Wallet", "GET", "/api/v1/wallet/ledger", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/wallet/ledger?page=0&size=20").as(r(vu).tok));
    add("Credit (customer)", "GET", "/api/v1/credit", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/credit").as(r(vu).tok));
    add("Credit (customer)", "GET", "/api/v1/credit/ledger", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/credit/ledger?page=0&size=20").as(r(vu).tok));

    // ---------- catalog (public)
    add("Catalog", "GET", "/api/v1/categories", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/categories"));
    add("Catalog", "GET", "/api/v1/products", "Public", 200).note("[page 0-99, size 20]").rq((vu, n, t) -> Rq.get(P + "/products?size=20&page=" + rnd().nextInt(100)));
    add("Catalog", "GET", "/api/v1/products", "Public", 200).note("[search q=Item <n>]").rq((vu, n, t) -> Rq.get(P + "/products?size=20&q=Item%20" + (1 + rnd().nextInt(19999))));
    add("Catalog", "GET", "/api/v1/products", "Public", 200).note("[by category, hot=false]").rq((vu, n, t) -> Rq.get(P + "/products?size=20&categoryId=" + pick(cats)));
    add("Catalog", "GET", "/api/v1/products/{id}", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/products/" + pick(samplePids)));
    add("Catalog", "GET", "/api/v1/product-groups", "Public", 200).note("[page 0-99, size 20]").rq((vu, n, t) -> Rq.get(P + "/product-groups?size=20&page=" + rnd().nextInt(100)));
    add("Catalog", "GET", "/api/v1/product-groups/{id}", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/product-groups/" + pick(groupIds)));
    add("Catalog", "GET", "/api/v1/hot-sale", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/hot-sale"));
    add("Catalog", "GET", "/api/v1/units", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/units"));
    add("Catalog", "GET", "/api/v1/coupons", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/coupons"));
    add("Reviews", "GET", "/api/v1/products/{productId}/reviews", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/products/" + pick(samplePids) + "/reviews?page=0&size=20"));
    add("Reviews", "GET", "/api/v1/product-groups/{id}/reviews", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/product-groups/" + pick(groupIds) + "/reviews?page=0&size=20"));
    add("Reviews", "GET", "/api/v1/products/{productId}/reviews/mine", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/products/" + pick(samplePids) + "/reviews/mine").as(w(vu).tok));
    add("Reviews", "POST", "/api/v1/products/{productId}/reviews", "Customer", 200).note("[verified purchase: fresh delivered order made in untimed prep]").pools(300, 1200)
        .prep(() -> { Cust c = w(rnd().nextInt(1000)); String p = hotP(); String o = deliveredOrder(c, p, 1); return o == null ? null : new Object[]{c, p}; })
        .rq((vu, n, t) -> { Object[] o = (Object[]) t; return Rq.post(P + "/products/" + o[1] + "/reviews").as(((Cust) o[0]).tok).json("{\"rating\":" + (1 + rnd().nextInt(5)) + ",\"comment\":\"Fresh and tasty " + hex8() + "\"}"); });

    // ---------- cart and orders (customer)
    add("Cart", "POST", "/api/v1/cart/quote", "Customer", 200).rq((vu, n, t) -> Rq.post(P + "/cart/quote").as(w(vu).tok)
        .json("{\"items\":[{\"productId\":\"" + hotP() + "\",\"qty\":" + (1 + rnd().nextInt(3)) + "},{\"productId\":\"" + hotP() + "\",\"qty\":1}],\"coinMode\":\"NONE\",\"fulfilment\":\"DELIVERY\"}"));
    add("Cart", "GET", "/api/v1/cart", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/cart").as(w(vu).tok));
    add("Cart", "PUT", "/api/v1/cart", "Customer", 200).rq((vu, n, t) -> Rq.put(P + "/cart").as(w(vu).tok).json("{\"items\":[{\"productId\":\"" + hotP() + "\",\"qty\":2},{\"productId\":\"" + hotP() + "\",\"qty\":1}],\"coinMode\":\"NONE\"}"));
    add("Cart", "DELETE", "/api/v1/cart", "Customer", 204).rq((vu, n, t) -> Rq.del(P + "/cart").as(w(vu).tok));
    add("Orders", "POST", "/api/v1/orders", "Customer", 201).note("[COD, delivery, 1-2 units of one of 60 products]")
        .rq((vu, n, t) -> { Cust c = w(vu); return Rq.post(P + "/orders").as(c.tok).idem().json(orderBody(c, hotP(), 1 + rnd().nextInt(2))); });
    add("Orders", "GET", "/api/v1/orders", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/orders?page=0&size=20").as(r(vu).tok));
    add("Orders", "GET", "/api/v1/orders/{id}", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/orders/" + r(vu).orders.get(0)).as(r(vu).tok));
    add("Orders", "POST", "/api/v1/orders/{id}/cancel", "Customer", 200).note("[PLACED order created in untimed prep]")
        .prep(() -> { Cust c = w(rnd().nextInt(1000)); String id = placeOrder(c, hotP(), 1); return id == null ? null : new Object[]{c, id}; })
        .rq((vu, n, t) -> { Object[] o = (Object[]) t; return Rq.post(P + "/orders/" + o[1] + "/cancel").as(((Cust) o[0]).tok).json("{\"reason\":\"Ordered by mistake\"}"); });
    add("Orders", "GET", "/api/v1/orders/{id}/reorder", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/orders/" + r(vu).orders.get(0) + "/reorder").as(r(vu).tok));
    add("Orders", "GET", "/api/v1/orders/{id}/returns", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/orders/" + r(vu).orders.get(0) + "/returns").as(r(vu).tok));
    add("Orders", "GET", "/api/v1/orders/stream", "Customer", 200).note("[SSE: time to first byte, then close]").rq((vu, n, t) -> Rq.get(P + "/orders/stream").as(w(vu).tok).stream());

    // ---------- payments
    add("Payments", "GET", "/api/v1/payment-methods", "Customer", 200).rq((vu, n, t) -> Rq.get(P + "/payment-methods").as(w(vu).tok));
    add("Payments", "POST", "/api/v1/payments/webhook/{provider}", "Public (Razorpay)", 400).note("[REJECTION PATH: unsigned call answers 400 BAD_SIGNATURE; a valid signed webhook cannot be produced]")
        .rq((vu, n, t) -> Rq.post(P + "/payments/webhook/razorpay").json("{\"event\":\"payment.captured\",\"payload\":{}}"));
    add("Payments", "GET", "/api/v1/admin/payment-methods", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/payment-methods").as(adm()));
    add("Payments", "PUT", "/api/v1/admin/payment-methods", "Admin", 200).note("[re-saves COD enabled]").rq((vu, n, t) -> Rq.put(P + "/admin/payment-methods").as(adm()).json("[{\"code\":\"COD\",\"enabled\":true}]"));
    add("Payments", "GET", "/api/v1/admin/refunds", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/refunds?page=0&size=20").as(adm()));
    add("Payments", "POST", "/api/v1/admin/refunds/{id}/retry", "Admin", 409).note("[REJECTION PATH: no FAILED refund exists (needs Razorpay); unknown id answers 409 REFUND_NOT_FAILED]")
        .rq((vu, n, t) -> Rq.post(P + "/admin/refunds/" + uuid() + "/retry").as(adm()));
    add("Payments", "GET", "/api/v1/admin/payments/anomalies", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/payments/anomalies?page=0&size=20").as(adm()));

    // ---------- admin: orders
    add("Admin orders", "GET", "/api/v1/admin/orders", "Admin/Cashier", 200).note("[page 0-49, size 20]").rq((vu, n, t) -> Rq.get(P + "/admin/orders?size=20&page=" + rnd().nextInt(50)).as(adm()));
    add("Admin orders", "GET", "/api/v1/admin/orders", "Admin/Cashier", 200).note("[status=PLACED, page 0-49]").rq((vu, n, t) -> Rq.get(P + "/admin/orders?size=20&status=PLACED&page=" + rnd().nextInt(50)).as(csh()));
    add("Admin orders", "GET", "/api/v1/admin/orders", "Admin/Cashier", 200).note("[search q=order number]").rq((vu, n, t) -> Rq.get(P + "/admin/orders?q=" + (1000 + rnd().nextInt(150000))).as(adm()));
    add("Admin orders", "GET", "/api/v1/admin/orders/{id}", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.get(P + "/admin/orders/" + pick(ordIds)).as(adm()));
    add("Admin orders", "POST", "/api/v1/admin/orders/{id}/status", "Admin/Cashier", 200).note("[PLACED -> CONFIRMED (deducts stock); order created in untimed prep]")
        .prep(() -> placeOrder(w(rnd().nextInt(1000)), hotP(), 1)).rq((vu, n, t) -> Rq.post(P + "/admin/orders/" + t + "/status").as(csh()).json("{\"status\":\"CONFIRMED\",\"note\":\"bench\"}"));
    add("Admin orders", "POST", "/api/v1/admin/orders/{id}/reject", "Admin/Cashier", 200).note("[order created in untimed prep]")
        .prep(() -> placeOrder(w(rnd().nextInt(1000)), hotP(), 1)).rq((vu, n, t) -> Rq.post(P + "/admin/orders/" + t + "/reject").as(adm()).json("{\"reason\":\"Item out of stock\"}"));
    add("Admin orders", "POST", "/api/v1/admin/orders/{id}/returns", "Admin/Cashier", 201).note("[return of 1 of 2 units, restocked; delivered order made in untimed prep]").pools(300, 1500)
        .prep(() -> { String p = hotP(); String o = deliveredOrder(w(rnd().nextInt(1000)), p, 2); return o == null ? null : new String[]{o, p}; })
        .rq((vu, n, t) -> { String[] o = (String[]) t; return Rq.post(P + "/admin/orders/" + o[0] + "/returns").as(adm()).idem().json("{\"reason\":\"Packet damaged\",\"items\":[{\"productId\":\"" + o[1] + "\",\"qty\":1,\"restock\":true}]}"); });
    add("Admin orders", "GET", "/api/v1/admin/orders/{id}/returns", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.get(P + "/admin/orders/" + pick(ordIds) + "/returns").as(adm()));
    add("Admin orders", "GET", "/api/v1/admin/orders/stream", "Admin/Cashier", 200).note("[SSE: time to first byte, then close]").rq((vu, n, t) -> Rq.get(P + "/admin/orders/stream").as(adm()).stream());

    // ---------- admin: catalog reads
    add("Admin catalog", "GET", "/api/v1/admin/products", "Admin", 200).note("[page 0-99, size 20]").rq((vu, n, t) -> Rq.get(P + "/admin/products?size=20&page=" + rnd().nextInt(100)).as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/products", "Admin", 200).note("[search q=Item <n>]").rq((vu, n, t) -> Rq.get(P + "/admin/products?size=20&q=Item%20" + (1 + rnd().nextInt(19999))).as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/products/{id}", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/products/" + pick(samplePids)).as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/products/{id}/variants", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/products/" + pick(samplePids) + "/variants").as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/stock-movements", "Admin", 200).note("[all products, page 0-19]").rq((vu, n, t) -> Rq.get(P + "/admin/stock-movements?size=20&page=" + rnd().nextInt(20)).as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/hot-banners", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/hot-banners").as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/units", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/units").as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/coupons", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/coupons").as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/coin-rules", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/coin-rules").as(adm()));
    add("Admin catalog", "GET", "/api/v1/admin/coin-settings", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/coin-settings").as(adm()));
    add("Admin catalog", "PUT", "/api/v1/admin/coin-settings", "Admin", 200).note("[re-saves 100 paise/coin, 20%, 6 months]").rq((vu, n, t) -> Rq.put(P + "/admin/coin-settings").as(adm()).json("{\"paisePerCoin\":100,\"maxRedeemPercent\":20,\"expiryMonths\":6}"));

    // ---------- admin: customers
    add("Admin customers", "GET", "/api/v1/admin/customers", "Admin", 200).note("[page 0-99, size 20]").rq((vu, n, t) -> Rq.get(P + "/admin/customers?size=20&page=" + rnd().nextInt(100)).as(adm()));
    add("Admin customers", "GET", "/api/v1/admin/customers", "Admin", 200).note("[search q=load-<hex>]").rq((vu, n, t) -> Rq.get(P + "/admin/customers?size=20&q=load-" + Integer.toHexString(rnd().nextInt(16))).as(adm()));
    add("Admin customers", "GET", "/api/v1/admin/customers/{id}", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/customers/" + pick(custIds)).as(adm()));
    add("Admin customers", "PATCH", "/api/v1/admin/customers/{id}", "Admin", 200).note("[active=true on bench customers]").rq((vu, n, t) -> Rq.patch(P + "/admin/customers/" + w(vu).userId).as(adm()).json("{\"active\":true}"));
    add("Admin customers", "POST", "/api/v1/admin/customers/{id}/coins", "Admin", 200).note("[+1 coin on bench customers]").rq((vu, n, t) -> Rq.post(P + "/admin/customers/" + w(vu).userId + "/coins").as(adm()).json("{\"delta\":1,\"reason\":\"bench goodwill\"}"));

    // ---------- admin: catalog writes
    add("Admin catalog writes", "POST", "/api/v1/admin/products", "Admin", 201).rq((vu, n, t) -> Rq.post(P + "/admin/products").as(adm()).json(productJson(pick(cats), "Bench Item " + hex8(), 15000, 12000)));
    add("Admin catalog writes", "PUT", "/api/v1/admin/products/{id}", "Admin", 200).note("[bench product per user; stock omitted]")
        .rq((vu, n, t) -> { String[] b = benchProducts(false).get(vu % benchA.size()); String body = productJson(b[2], "Fixture Item " + b[0].substring(0, 6), 5000, 4000); return Rq.put(P + "/admin/products/" + b[0]).as(adm()).json(body.replace("\"qtyOnHand\":40,", "")); });
    add("Admin catalog writes", "POST", "/api/v1/admin/products/{id}/variants", "Admin", 201).rq((vu, n, t) -> { String[] b = benchProducts(true).get(vu % benchV.size());
      return Rq.post(P + "/admin/products/" + b[0] + "/variants").as(adm()).json("{\"variantName\":\"V" + hex8() + "\",\"packSize\":\"1 kg\",\"mrpPaise\":28000,\"pricePaise\":25000,\"active\":true,\"qtyOnHand\":10,\"lowThreshold\":5}"); });
    add("Admin catalog writes", "PUT", "/api/v1/admin/product-groups/{id}", "Admin", 200).rq((vu, n, t) -> { String[] b = benchProducts(false).get(vu % benchA.size());
      return Rq.put(P + "/admin/product-groups/" + b[1]).as(adm()).json("{\"categoryId\":\"" + b[2] + "\",\"nameEn\":\"Fixture group " + b[1].substring(0, 6) + "\",\"nameGu\":\"Bench\",\"description\":\"" + hex8() + "\",\"hotSale\":false,\"earnsCoins\":true,\"coinRedeemable\":true,\"active\":true}"); });
    add("Admin catalog writes", "POST", "/api/v1/admin/products/{id}/stock", "Admin", 200).rq((vu, n, t) -> { String[] b = benchProducts(false).get(vu % benchA.size()); return Rq.post(P + "/admin/products/" + b[0] + "/stock").as(adm()).json("{\"delta\":1,\"reason\":\"RESTOCK\"}"); });
    add("Admin catalog writes", "POST", "/api/v1/admin/products/{id}/image", "Admin", 200).note("[multipart, 96x96 PNG]").rq((vu, n, t) -> { String[] b = benchProducts(false).get(vu % benchA.size()); return Rq.post(P + "/admin/products/" + b[0] + "/image").as(adm()).file("file", "p.png", png()); });
    add("Admin catalog writes", "POST", "/api/v1/admin/categories", "Admin", 201).rq((vu, n, t) -> Rq.post(P + "/admin/categories").as(adm()).json("{\"nameEn\":\"Bench cat " + hex8() + "\",\"nameGu\":\"બેન્ચ\",\"sortOrder\":99,\"active\":false}"));
    add("Admin catalog writes", "PUT", "/api/v1/admin/categories/{id}", "Admin", 200).rq((vu, n, t) -> { benchOthers(); return Rq.put(P + "/admin/categories/" + benchCat.get(vu % benchCat.size())).as(adm()).json("{\"nameEn\":\"Fixture cat " + hex8() + "\",\"nameGu\":\"બેન્ચ\",\"sortOrder\":99,\"active\":false}"); });
    add("Admin catalog writes", "POST", "/api/v1/admin/hot-banners", "Admin", 201).rq((vu, n, t) -> Rq.post(P + "/admin/hot-banners").as(adm()).json("{\"title\":\"Bench " + hex8() + "\",\"subtitle\":\"Up to 20% off\",\"active\":false}"));
    add("Admin catalog writes", "PUT", "/api/v1/admin/hot-banners/{id}", "Admin", 200).rq((vu, n, t) -> { benchOthers(); return Rq.put(P + "/admin/hot-banners/" + benchBanner.get(vu % benchBanner.size())).as(adm()).json("{\"title\":\"Fixture " + hex8() + "\",\"subtitle\":\"s\",\"active\":false}"); });
    add("Admin catalog writes", "DELETE", "/api/v1/admin/hot-banners/{id}", "Admin", 204).prep(() -> str(post(P + "/admin/hot-banners", adm(), "{\"title\":\"Tmp " + hex8() + "\",\"subtitle\":\"s\",\"active\":false}").body(), "id"))
        .rq((vu, n, t) -> Rq.del(P + "/admin/hot-banners/" + t).as(adm()));
    add("Admin catalog writes", "POST", "/api/v1/admin/units", "Admin", 201).rq((vu, n, t) -> Rq.post(P + "/admin/units").as(adm()).json("{\"code\":\"u" + hex8() + "\",\"nameEn\":\"Bench\",\"nameGu\":\"\",\"soldBy\":\"UNIT\",\"sortOrder\":90,\"active\":false}"));
    add("Admin catalog writes", "PUT", "/api/v1/admin/units/{code}", "Admin", 200).rq((vu, n, t) -> { benchOthers(); String c = benchUnit.get(vu % benchUnit.size()); return Rq.put(P + "/admin/units/" + c).as(adm()).json("{\"code\":\"" + c + "\",\"nameEn\":\"Fixture " + hex8() + "\",\"nameGu\":\"\",\"soldBy\":\"UNIT\",\"sortOrder\":90,\"active\":false}"); });
    add("Admin catalog writes", "DELETE", "/api/v1/admin/units/{code}", "Admin", 204).prep(() -> { String c = "d" + hex8(); post(P + "/admin/units", adm(), "{\"code\":\"" + c + "\",\"nameEn\":\"Tmp\",\"nameGu\":\"\",\"soldBy\":\"UNIT\",\"sortOrder\":91,\"active\":false}"); return c; })
        .rq((vu, n, t) -> Rq.del(P + "/admin/units/" + t).as(adm()));
    add("Admin catalog writes", "POST", "/api/v1/admin/coupons", "Admin", 201).note("[not-yet-started coupon]").rq((vu, n, t) -> Rq.post(P + "/admin/coupons").as(adm()).json(couponJson("BC" + hex8().toUpperCase() + rnd().nextInt(99), 5)));
    add("Admin catalog writes", "PUT", "/api/v1/admin/coupons/{id}", "Admin", 200).rq((vu, n, t) -> { benchOthers(); String id = benchCoupon.get(vu % benchCoupon.size()); return Rq.put(P + "/admin/coupons/" + id).as(adm()).json(couponJson("FXC" + id.substring(0, 6).toUpperCase() + "X", 5 + rnd().nextInt(10))); });
    add("Admin catalog writes", "DELETE", "/api/v1/admin/coupons/{id}", "Admin", 204).prep(() -> str(post(P + "/admin/coupons", adm(), couponJson("TMP" + hex8().toUpperCase(), 5)).body(), "id"))
        .rq((vu, n, t) -> Rq.del(P + "/admin/coupons/" + t).as(adm()));
    add("Admin catalog writes", "POST", "/api/v1/admin/coin-rules", "Admin", 201).rq((vu, n, t) -> Rq.post(P + "/admin/coin-rules").as(adm()).json(coinRuleJson(10000)));
    add("Admin catalog writes", "PUT", "/api/v1/admin/coin-rules/{id}", "Admin", 200).rq((vu, n, t) -> { benchOthers(); return Rq.put(P + "/admin/coin-rules/" + benchCoin.get(vu % benchCoin.size())).as(adm()).json(coinRuleJson(10000 + rnd().nextInt(500), "Fixture")); });
    add("Admin catalog writes", "DELETE", "/api/v1/admin/coin-rules/{id}", "Admin", 204).prep(() -> str(post(P + "/admin/coin-rules", adm(), coinRuleJson(10000)).body(), "id"))
        .rq((vu, n, t) -> Rq.del(P + "/admin/coin-rules/" + t).as(adm()));

    // ---------- shop details and staff
    add("Shop", "GET", "/api/v1/shop-settings", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/shop-settings"));
    add("Shop", "GET", "/api/v1/admin/shop-profile", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/shop-profile").as(adm()));
    add("Shop", "PUT", "/api/v1/admin/shop-profile", "Admin", 200).note("[opening hours + holiday note; restored afterwards]").rq((vu, n, t) -> Rq.put(P + "/admin/shop-profile").as(adm()).json(shopProfileJson("Bench " + (n % 7))))
        .after(() -> put(P + "/admin/shop-profile", adm(), shopProfileJson("")));
    add("Shop", "POST", "/api/v1/admin/shop-profile/logo", "Admin", 200).note("[multipart, 96x96 PNG]").rq((vu, n, t) -> Rq.post(P + "/admin/shop-profile/logo").as(adm()).file("file", "logo.png", png()));
    add("Shop", "DELETE", "/api/v1/admin/shop-profile/logo", "Admin", 204, 200).note("[logo uploaded in untimed prep]").prep(() -> { send(Rq.post(P + "/admin/shop-profile/logo").as(adm()).file("file", "logo.png", png())); return Boolean.TRUE; }).pools(60, 150)
        .rq((vu, n, t) -> Rq.del(P + "/admin/shop-profile/logo").as(adm()));
    add("Shop", "GET", "/api/v1/admin/shop-settings", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/shop-settings").as(adm()));
    add("Shop", "PUT", "/api/v1/admin/shop-settings", "Admin", 200).note("[bill header details; promise text varies; restored afterwards]").rq((vu, n, t) -> Rq.put(P + "/admin/shop-settings").as(adm()).json(shopSettingsJson("Fresh every day " + (n % 5))))
        .after(() -> put(P + "/admin/shop-settings", adm(), shopSettingsJson("")));
    add("Staff", "GET", "/api/v1/admin/staff", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/staff").as(adm()));
    add("Staff", "POST", "/api/v1/admin/staff", "Admin", 201).rq((vu, n, t) -> Rq.post(P + "/admin/staff").as(adm()).json("{\"email\":\"benchstaff-" + uuid() + "@example.com\",\"name\":\"Bench Staff\"}"));
    add("Staff", "PATCH", "/api/v1/admin/staff/{id}", "Admin", 200).rq((vu, n, t) -> { benchOthers(); return Rq.patch(P + "/admin/staff/" + benchStaff.get(vu % benchStaff.size())).as(adm()).json("{\"active\":true}"); });
    add("Staff", "GET", "/api/v1/admin/products/{id}/pos", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/products/" + pick(samplePids) + "/pos").as(adm()));
    add("Staff", "PUT", "/api/v1/admin/products/{id}/pos", "Admin", 200).note("[counter attributes + barcode of a bench product]").rq((vu, n, t) -> { String[] b = benchProducts(false).get(vu % benchA.size());
      return Rq.put(P + "/admin/products/" + b[0] + "/pos").as(adm()).json("{\"soldBy\":\"UNIT\",\"gstPercent\":5,\"hsn\":\"1006\",\"barcodes\":[\"BN" + b[0].replace("-", "").substring(0, 12) + "\"]}"); });
    add("Staff", "PUT", "/api/v1/admin/me/approval-pin", "Admin", 200, 204).rq((vu, n, t) -> Rq.put(P + "/admin/me/approval-pin").as(adm()).json("{\"pin\":\"4821\"}"));

    // ---------- POS
    add("POS", "GET", "/api/v1/pos/shop", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/shop").as(csh()));
    add("POS", "GET", "/api/v1/pos/barcode/{code}", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/barcode/" + pick(barcodes)).as(csh()));
    add("POS", "GET", "/api/v1/pos/products", "Cashier/Admin", 200).note("[q=Item <n>]").rq((vu, n, t) -> Rq.get(P + "/pos/products?size=20&q=Item%20" + (1 + rnd().nextInt(19999))).as(csh()));
    add("POS", "POST", "/api/v1/pos/quote", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.post(P + "/pos/quote").as(csh()).json("{\"items\":[{\"productId\":\"" + hotP() + "\",\"qty\":2},{\"productId\":\"" + hotP() + "\",\"qty\":1}]}"));
    add("POS", "POST", "/api/v1/pos/sales", "Cashier/Admin", 201).note("[cash bill, 1-3 units of one of 60 products]").rq((vu, n, t) -> Rq.post(P + "/pos/sales").as(csh()).idem().json(saleBody(hotP(), 1 + rnd().nextInt(3))));
    add("POS", "GET", "/api/v1/pos/sales", "Cashier/Admin", 200).note("[today's bills, latest 200]").rq((vu, n, t) -> Rq.get(P + "/pos/sales").as(csh()));
    add("POS", "GET", "/api/v1/pos/sales/{id}", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/sales/" + pick(saleIds)).as(csh()));
    add("POS", "GET", "/api/v1/pos/sales/{id}/receipt", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/sales/" + pick(saleIds) + "/receipt?width=32").as(csh()));
    add("POS", "POST", "/api/v1/pos/sales/{id}/returns", "Cashier/Admin", 201).note("[return 1 of 2 units; bill made in untimed prep]").pools(300, 1500)
        .prep(() -> ringSale(hotP(), 2)).rq((vu, n, t) -> { String[] s = (String[]) t; return Rq.post(P + "/pos/sales/" + s[0] + "/returns").as(csh()).idem().json("{\"items\":[{\"saleItemId\":\"" + s[1] + "\",\"qty\":1}],\"reason\":\"Damaged\"}"); });
    add("POS", "GET", "/api/v1/pos/sales/{id}/returns", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/sales/" + pick(saleIds) + "/returns").as(csh()));
    add("POS", "GET", "/api/v1/pos/upi-link", "Cashier/Admin", 200).note("[UPI id set to vijaymall@okbank for this case, restored afterwards]").rq((vu, n, t) -> { ensureUpi(); return Rq.get(P + "/pos/upi-link?amountPaise=" + (10000 + rnd().nextInt(90000)) + "&note=bench").as(csh()); })
        .after(() -> { upiSet = false; put(P + "/admin/shop-settings", adm(), shopSettingsJson("")); });
    add("POS", "POST", "/api/v1/pos/holds", "Cashier/Admin", 201).rq((vu, n, t) -> Rq.post(P + "/pos/holds").as(csh()).json("{\"label\":\"Mrs Shah " + hex8() + "\",\"cart\":" + cartJson(hotP(), 2) + "}"));
    add("POS", "GET", "/api/v1/pos/holds", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/holds").as(csh()));
    add("POS", "GET", "/api/v1/pos/holds/{id}", "Cashier/Admin", 200).rq((vu, n, t) -> { String h = (String) CURRENT.state.computeIfAbsent(vu, k -> str(post(P + "/pos/holds", csh(), "{\"label\":\"read\",\"cart\":" + cartJson(hotP(), 1) + "}").body(), "id")); return Rq.get(P + "/pos/holds/" + h).as(csh()); });
    add("POS", "DELETE", "/api/v1/pos/holds/{id}", "Cashier/Admin", 204).prep(() -> str(post(P + "/pos/holds", csh(), "{\"label\":\"tmp\",\"cart\":" + cartJson(hotP(), 1) + "}").body(), "id"))
        .rq((vu, n, t) -> Rq.del(P + "/pos/holds/" + t).as(csh()));
    add("POS", "GET", "/api/v1/pos/reports/day", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/reports/day").as(csh()));
    add("POS shifts", "POST", "/api/v1/pos/shifts/open", "Cashier/Admin", 200, 201).note("[a fresh cashier account is created and signed in during untimed prep]").pools(150, 700)
        .prep(EndpointBench::freshStaffToken).rq((vu, n, t) -> Rq.post(P + "/pos/shifts/open").as((String) t).json("{\"openingFloatPaise\":200000}"));
    add("POS shifts", "GET", "/api/v1/pos/shifts/current", "Cashier/Admin", 200).note("[cashier has an open shift (opened for this case)]").rq((vu, n, t) -> { ensureShift(); return Rq.get(P + "/pos/shifts/current").as(csh()); });
    add("POS shifts", "POST", "/api/v1/pos/shifts/close", "Cashier/Admin", 200).note("[fresh cashier with open shift made in untimed prep; counted = opening float]").pools(150, 700)
        .prep(() -> { String tk = freshStaffToken(); if (tk == null) return null; Res o = post(P + "/pos/shifts/open", tk, "{\"openingFloatPaise\":200000}"); return o.status() < 300 ? tk : null; })
        .rq((vu, n, t) -> Rq.post(P + "/pos/shifts/close").as((String) t).json("{\"countedCashPaise\":200000,\"note\":\"bench\"}"));
    add("POS shifts", "GET", "/api/v1/admin/pos/shifts", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/pos/shifts?page=0&size=20").as(adm()));
    add("POS sync", "GET", "/api/v1/pos/sync/status", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/sync/status").as(csh()));
    add("POS sync", "POST", "/api/v1/pos/sync/sales", "Cashier/Admin", 200).note("[2 offline bills per upload]").rq((vu, n, t) -> {
      String p1 = hotP(), p2 = hotP(); String soldAt = iso(Instant.now().minusSeconds(60));
      return Rq.post(P + "/pos/sync/sales").as(csh()).json("{\"sales\":[" + offlineBill(p1, soldAt, false) + "," + offlineBill(p2, soldAt, false) + "]}"); });

    // ---------- credit (counter and admin)
    add("Credit", "GET", "/api/v1/pos/credit/account", "Cashier/Admin", 200).rq((vu, n, t) -> { creditSetup(); return Rq.get(P + "/pos/credit/account?mobile=" + creditMobiles.get(vu % creditMobiles.size())).as(csh()); });
    add("Credit", "GET", "/api/v1/pos/credit/ledger", "Cashier/Admin", 200).rq((vu, n, t) -> { creditSetup(); return Rq.get(P + "/pos/credit/ledger?page=0&size=20&mobile=" + creditMobiles.get(vu % creditMobiles.size())).as(csh()); });
    add("Credit", "POST", "/api/v1/pos/credit/payments", "Cashier/Admin", 201).note("[Rs 1 cash repayment against a bench debtor]").rq((vu, n, t) -> { creditSetup(); return Rq.post(P + "/pos/credit/payments").as(csh()).idem()
        .json("{\"customerMobile\":\"" + creditMobiles.get(vu % creditMobiles.size()) + "\",\"payments\":[{\"mode\":\"CASH\",\"amountPaise\":100}],\"note\":\"bench\"}"); });
    add("Credit", "PUT", "/api/v1/admin/credit/limit", "Admin", 200).rq((vu, n, t) -> { creditSetup(); return Rq.put(P + "/admin/credit/limit").as(adm()).json("{\"mobile\":\"" + creditMobiles.get(vu % creditMobiles.size()) + "\",\"limitPaise\":" + (10000000 + rnd().nextInt(1000)) + "}"); });
    add("Credit", "GET", "/api/v1/admin/credit/accounts/{customerId}", "Admin", 200).rq((vu, n, t) -> { creditSetup(); return Rq.get(P + "/admin/credit/accounts/" + creditIds.get(vu % creditIds.size())).as(adm()); });
    add("Credit", "GET", "/api/v1/admin/credit/accounts/{customerId}/ledger", "Admin", 200).rq((vu, n, t) -> { creditSetup(); return Rq.get(P + "/admin/credit/accounts/" + creditIds.get(vu % creditIds.size()) + "/ledger?page=0&size=20").as(adm()); });
    add("Credit", "PUT", "/api/v1/admin/credit/accounts/{customerId}/limit", "Admin", 200).rq((vu, n, t) -> { creditSetup(); return Rq.put(P + "/admin/credit/accounts/" + creditIds.get(vu % creditIds.size()) + "/limit").as(adm()).json("{\"limitPaise\":" + (10000000 + rnd().nextInt(1000)) + "}"); });
    add("Credit", "POST", "/api/v1/admin/credit/accounts/{customerId}/adjust", "Admin", 200).note("[+Rs 1 correction]").rq((vu, n, t) -> { creditSetup(); return Rq.post(P + "/admin/credit/accounts/" + creditIds.get(vu % creditIds.size()) + "/adjust").as(adm()).json("{\"amountPaise\":100,\"note\":\"bench correction\"}"); });

    // ---------- inventory: defective goods and offline exceptions
    add("Defective stock", "GET", "/api/v1/pos/stock/defective", "Cashier/Admin", 200).rq((vu, n, t) -> Rq.get(P + "/pos/stock/defective?page=0&size=20").as(csh()));
    add("Defective stock", "GET", "/api/v1/admin/stock/defective", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/stock/defective?page=0&size=20").as(adm()));
    add("Defective stock", "GET", "/api/v1/admin/stock/defective/summary", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/stock/defective/summary").as(adm()));
    add("Defective stock", "POST", "/api/v1/pos/stock/defective/{id}/review", "Cashier/Admin", 200).note("[RESTOCK decision; defective batch from a no-restock return made in untimed prep]").pools(150, 600)
        .bulk(n -> defectiveBatches(n, false)).rq((vu, n, t) -> Rq.post(P + "/pos/stock/defective/" + t + "/review").as(adm()).json("{\"decision\":\"RESTOCK\",\"note\":\"looks fine\"}"));
    add("Defective stock", "POST", "/api/v1/admin/stock/defective/{id}/resolve", "Admin", 200).note("[WRITE_OFF; batch already confirmed defective in untimed prep]").pools(150, 600)
        .bulk(n -> defectiveBatches(n, true)).rq((vu, n, t) -> Rq.post(P + "/admin/stock/defective/" + t + "/resolve").as(adm()).json("{\"action\":\"WRITE_OFF\",\"note\":\"bench\"}"));
    add("Offline exceptions", "GET", "/api/v1/admin/pos/offline-exceptions", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/pos/offline-exceptions?page=0&size=20").as(adm()));
    add("Offline exceptions", "POST", "/api/v1/admin/pos/offline-exceptions/{id}/review", "Admin", 200).note("[OVERSOLD exceptions created by uploading offline bills for a zero-stock product in untimed prep]").pools(300, 1500)
        .bulk(EndpointBench::offlineExceptions).rq((vu, n, t) -> Rq.post(P + "/admin/pos/offline-exceptions/" + t + "/review").as(adm()).json("{\"note\":\"checked\"}"));

    // ---------- purchasing and stock
    add("Purchasing", "POST", "/api/v1/admin/purchases", "Admin", 201).note("[3 lines]").rq((vu, n, t) -> Rq.post(P + "/admin/purchases").as(adm()).idem()
        .json("{\"supplierName\":\"Shah Traders\",\"invoiceNo\":\"ST-" + hex8() + "\",\"note\":\"Morning delivery\",\"items\":[{\"productId\":\"" + hp.get(0) + "\",\"qty\":24,\"unitCostPaise\":9500},{\"productId\":\"" + hp.get(1) + "\",\"qty\":12,\"unitCostPaise\":4500},{\"productId\":\"" + hp.get(2) + "\",\"qty\":6,\"unitCostPaise\":1500}]}"));
    add("Purchasing", "GET", "/api/v1/admin/purchases", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/purchases?page=0&size=20").as(adm()));
    add("Purchasing", "GET", "/api/v1/admin/purchases/{id}", "Admin", 200).rq((vu, n, t) -> { String id = (String) CURRENT.state.computeIfAbsent(-1, k -> str(get(P + "/admin/purchases?size=1", adm()).body(), "id")); return Rq.get(P + "/admin/purchases/" + id).as(adm()); });
    add("Purchasing", "POST", "/api/v1/admin/stock-takes", "Admin", 200, 201).note("[5 lines set to 2,000,000 so later cases keep stock]").rq((vu, n, t) -> Rq.post(P + "/admin/stock-takes").as(adm())
        .json("{\"note\":\"bench count\",\"items\":[" + String.join(",", hp.subList(3 + vu % 10, 8 + vu % 10).stream().map(p -> "{\"productId\":\"" + p + "\",\"countedQty\":2000000}").toList()) + "]}"));

    // ---------- reports
    add("Reports", "GET", "/api/v1/admin/dashboard", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/dashboard").as(adm()));
    add("Reports", "GET", "/api/v1/admin/reports/summary", "Admin", 200).note("[period=month, served from the ~1 min cache]").rq((vu, n, t) -> Rq.get(P + "/admin/reports/summary?period=month").as(adm()));
    add("Reports", "GET", "/api/v1/admin/reports/summary", "Admin", 200).note("[cache-defeating: from=random day in last 300 days, to=today]").rq((vu, n, t) -> Rq.get(P + "/admin/reports/summary?from=" + LocalDate.now(IST).minusDays(30 + rnd().nextInt(270))).as(adm()));
    add("Reports", "GET", "/api/v1/admin/reports/credit", "Admin", 200).note("[period=month]").rq((vu, n, t) -> Rq.get(P + "/admin/reports/credit?period=month").as(adm()));
    add("Reports", "GET", "/api/v1/admin/credit/accounts", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/credit/accounts?page=0&size=20").as(adm()));
    add("Reports", "GET", "/api/v1/admin/reports/counter-controls", "Admin", 200).note("[period=month]").rq((vu, n, t) -> Rq.get(P + "/admin/reports/counter-controls?period=month").as(adm()));
    add("Reports", "GET", "/api/v1/admin/stock/low", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/stock/low?limit=50").as(adm()));
    add("Reports", "GET", "/api/v1/admin/audit-log", "Admin", 200).note("[latest page]").rq((vu, n, t) -> Rq.get(P + "/admin/audit-log?page=0&size=20").as(adm()));
    add("Reports", "GET", "/api/v1/admin/audit-log", "Admin", 200).note("[action=SALE, page 0-49]").rq((vu, n, t) -> Rq.get(P + "/admin/audit-log?action=SALE&size=20&page=" + rnd().nextInt(50)).as(adm()));

    // ---------- admin configuration, logs, caches
    add("Config", "GET", "/api/v1/admin/config", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/config").as(adm()));
    add("Config", "PUT", "/api/v1/admin/config", "Admin", 200).note("[toggles pos.cashierReturnDays 7/8; cleared afterwards]").rq((vu, n, t) -> Rq.put(P + "/admin/config").as(adm()).json("{\"changes\":[{\"key\":\"pos.cashierReturnDays\",\"value\":\"" + (7 + n % 2) + "\"}]}"))
        .after(() -> put(P + "/admin/config", adm(), "{\"changes\":[{\"key\":\"pos.cashierReturnDays\",\"value\":\"\"}]}"));
    add("Config", "GET", "/api/v1/admin/config/bootstrap", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/config/bootstrap").as(adm()));
    add("Config", "GET", "/api/v1/admin/logs/status", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/logs/status").as(adm()));
    add("Config", "GET", "/api/v1/admin/cache", "Admin", 200).rq((vu, n, t) -> Rq.get(P + "/admin/cache").as(adm()));
    add("Config", "POST", "/api/v1/admin/cache/refresh", "Admin", 200, 204).rq((vu, n, t) -> Rq.post(P + "/admin/cache/refresh").as(adm()));
    add("Config", "POST", "/api/v1/admin/cache/refresh/{name}", "Admin", 200, 204).note("[name=categories]").rq((vu, n, t) -> Rq.post(P + "/admin/cache/refresh/categories").as(adm()));

    // ---------- flash sales
    add("Flash sales", "GET", "/api/v1/flash-sales/active", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/flash-sales/active"));
    add("Flash sales", "GET", "/api/v1/flash-sales/upcoming", "Public", 200).rq((vu, n, t) -> Rq.get(P + "/flash-sales/upcoming"));
    add("Flash sales", "GET", "/api/v1/admin/flash-sales", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.get(P + "/admin/flash-sales?page=0&size=20").as(adm()));
    add("Flash sales", "GET", "/api/v1/admin/flash-sales/report", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.get(P + "/admin/flash-sales/report").as(adm()));
    add("Flash sales", "GET", "/api/v1/admin/flash-sales/{id}", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.get(P + "/admin/flash-sales/" + flashRef(vu)).as(adm()));
    add("Flash sales", "GET", "/api/v1/admin/flash-sales/{id}/report", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.get(P + "/admin/flash-sales/" + flashRef(vu) + "/report").as(adm()));
    add("Flash sales", "POST", "/api/v1/admin/flash-sales", "Admin/Cashier", 201).note("[scheduled sale with 3 products, own time slot]").rq((vu, n, t) -> { Instant[] w = slotWindow();
      return Rq.post(P + "/admin/flash-sales").as(adm()).json(flashJson("Bench " + hex8(), w[0], w[1], nextFlashPid(), nextFlashPid(), nextFlashPid())); });
    add("Flash sales", "PATCH", "/api/v1/admin/flash-sales/{id}", "Admin/Cashier", 200).rq((vu, n, t) -> Rq.patch(P + "/admin/flash-sales/" + flashRef(vu)).as(adm()).json("{\"name\":\"Fixture " + hex8() + "\",\"bannerText\":\"Up to 25% off\"}"));
    add("Flash sales", "POST", "/api/v1/admin/flash-sales/{id}/items", "Admin/Cashier", 201, 200).note("[sale + 1 item made in untimed prep; the call adds a second product]").pools(300, 1500)
        .prep(() -> newScheduledSale(1)).rq((vu, n, t) -> Rq.post(P + "/admin/flash-sales/" + ((String[]) t)[0] + "/items").as(adm()).json("{\"productId\":\"" + nextFlashPid() + "\",\"discountKind\":\"PERCENT\",\"discountValue\":30,\"maxQty\":20,\"perCustomerLimit\":2}"));
    add("Flash sales", "PUT", "/api/v1/admin/flash-sales/{id}/items/{itemId}", "Admin/Cashier", 200).note("[sale with an item made in untimed prep]").pools(300, 1500)
        .prep(() -> newScheduledSale(1)).rq((vu, n, t) -> { String[] s = (String[]) t; return Rq.put(P + "/admin/flash-sales/" + s[0] + "/items/" + s[1]).as(adm()).json("{\"discountKind\":\"PERCENT\",\"discountValue\":25,\"maxQty\":80,\"perCustomerLimit\":2}"); });
    add("Flash sales", "DELETE", "/api/v1/admin/flash-sales/{id}/items/{itemId}", "Admin/Cashier", 200, 204).note("[sale with 2 items made in untimed prep]").pools(300, 1500)
        .prep(() -> newScheduledSale(2)).rq((vu, n, t) -> { String[] s = (String[]) t; return Rq.del(P + "/admin/flash-sales/" + s[0] + "/items/" + s[1]).as(adm()); });
    add("Flash sales", "POST", "/api/v1/admin/flash-sales/{id}/start-now", "Admin/Cashier", 200).note("[scheduled sale made in untimed prep; started sales are ended again afterwards]").pools(300, 1200)
        .prep(() -> { Instant s = Instant.now().plus(2, ChronoUnit.HOURS); Res r = post(P + "/admin/flash-sales", adm(), flashJson("Bench " + hex8(), s, s.plus(1, ChronoUnit.HOURS), nextFlashPid())); return r.status() == 201 ? str(r.body(), "id") : null; })
        .rq((vu, n, t) -> Rq.post(P + "/admin/flash-sales/" + t + "/start-now").as(adm()))
        .hook((vu, t, res) -> { if (res.status() == 200) STARTED.add((String) t); }).after(() -> { STARTED.parallelStream().forEach(id -> post(P + "/admin/flash-sales/" + id + "/end-now", adm(), null)); STARTED.clear(); });
    add("Flash sales", "POST", "/api/v1/admin/flash-sales/{id}/end-now", "Admin/Cashier", 200).note("[running sale made in untimed prep]").pools(300, 1200)
        .prep(() -> { Res r = post(P + "/admin/flash-sales", adm(), flashJson("Bench " + hex8(), null, Instant.now().plus(1, ChronoUnit.HOURS), nextFlashPid())); return r.status() == 201 ? str(r.body(), "id") : null; })
        .rq((vu, n, t) -> Rq.post(P + "/admin/flash-sales/" + t + "/end-now").as(adm()));
    add("Flash sales", "POST", "/api/v1/admin/flash-sales/{id}/cancel", "Admin/Cashier", 200).note("[scheduled sale made in untimed prep]").pools(300, 1500)
        .prep(() -> { String[] s = newScheduledSale(1); return s == null ? null : s[0]; }).rq((vu, n, t) -> Rq.post(P + "/admin/flash-sales/" + t + "/cancel").as(adm()));
    add("Flash sales", "POST", "/api/v1/admin/flash-sales/{id}/duplicate", "Admin/Cashier", 201, 200).note("[copies a 3-product sale into a new time slot]").rq((vu, n, t) -> { Instant[] w = slotWindow();
      return Rq.post(P + "/admin/flash-sales/" + flashRef(vu) + "/duplicate").as(adm()).json("{\"name\":\"Copy " + hex8() + "\",\"startsAt\":\"" + iso(w[0]) + "\",\"endsAt\":\"" + iso(w[1]) + "\"}"); });
  }

  static final Set<String> STARTED = ConcurrentHashMap.newKeySet();
  static final ConcurrentHashMap<Integer, String> flashRefs = new ConcurrentHashMap<>();
  /** a scheduled 3-product sale per user used by the read / patch / duplicate cases */
  static String flashRef(int vu) {
    return flashRefs.computeIfAbsent(vu % 16, k -> { String[] s = newScheduledSale(3, "Fixture"); return s == null ? "00000000-0000-0000-0000-000000000000" : s[0]; });
  }

  static volatile boolean shiftOpen;
  static synchronized void ensureShift() { if (!shiftOpen) { post("/api/v1/pos/shifts/open", csh(), "{\"openingFloatPaise\":200000}"); shiftOpen = true; } }
  static volatile boolean upiSet;
  static synchronized void ensureUpi() { if (!upiSet) { put("/api/v1/admin/shop-settings", adm(), shopSettingsJson("").replace("\"upiId\":\"\"", "\"upiId\":\"vijaymall@okbank\"")); upiSet = true; } }
  static String shopProfileJson(String holiday) {
    StringBuilder h = new StringBuilder();
    for (String d : new String[]{"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"}) { if (h.length() > 0) h.append(","); h.append("{\"day\":\"").append(d).append("\",\"open\":\"09:00\",\"close\":\"21:00\",\"closed\":false}"); }
    return "{\"email\":\"\",\"whatsapp\":\"\",\"mapUrl\":\"\",\"website\":\"\",\"instagram\":\"\",\"facebook\":\"\",\"hours\":[" + h + "],\"holidayNote\":\"" + holiday + "\",\"onlineOrdersEnabled\":true,\"onlineOrdersMessage\":\"\"}";
  }
  static String shopSettingsJson(String promise) {
    return "{\"shopName\":\"Vijay Super Mall\",\"address\":\"\",\"phone\":\"\",\"gstin\":\"\",\"stateCode\":\"24\",\"upiId\":\"\",\"invoicePrefix\":\"VSM\",\"receiptFooter\":\"Thank you! Visit again.\",\"weighedPrefix\":\"2\",\"weighedMode\":\"WEIGHT_GRAMS\",\"promise\":\"" + promise + "\",\"details\":\"\"}";
  }
  static String offlineBill(String pid, String soldAt, boolean discount) {
    long tot = posQuote(pid, 1);
    long pay = discount ? tot - 100 : tot;
    return "{\"idempotencyKey\":\"" + uuid() + "\",\"soldAt\":\"" + soldAt + "\",\"cart\":" + cartJson(pid, 1) + ",\"payments\":[{\"mode\":\"CASH\",\"amountPaise\":" + pay + ",\"reference\":null}]"
        + (discount ? ",\"manualDiscount\":{\"amountPaise\":100,\"reason\":\"Damaged pack\",\"approverPin\":\"4821\"}" : "") + "}";
  }

  // bulk pool builders --------------------------------------------------------------
  static List<Object> defectiveBatches(int n, boolean confirmed) {
    Set<String> before = new HashSet<>(pendingDefective());
    ExecutorService ex = Executors.newFixedThreadPool(USERS);
    List<Future<?>> fs = new ArrayList<>();
    for (int i = 0; i < n; i++) fs.add(ex.submit(() -> {
      String p = hotP(); String o = deliveredOrder(w(rnd().nextInt(1000)), p, 1);
      if (o == null) return;
      Res r = postI("/api/v1/admin/orders/" + o + "/returns", csh(), "{\"reason\":\"Packet damaged\",\"items\":[{\"productId\":\"" + p + "\",\"qty\":1,\"restock\":false}]}");
      if (r.status() != 201) System.out.println("  prep: return failed " + r.status() + " " + trunc(r.body(), 160));
    }));
    for (var f : fs) try { f.get(); } catch (Exception e) { }
    ex.shutdown();
    List<Object> out = new ArrayList<>();
    for (String id : pendingDefective()) if (!before.contains(id)) out.add(id);
    if (confirmed) {
      List<Object> ok = Collections.synchronizedList(new ArrayList<>());
      out.parallelStream().forEach(id -> { Res r = post("/api/v1/pos/stock/defective/" + id + "/review", adm(), "{\"decision\":\"CONFIRM_DEFECTIVE\",\"note\":\"bench\"}"); if (r.status() == 200) ok.add(id); else System.out.println("  prep: confirm failed " + r.status() + " " + trunc(r.body(), 160)); });
      return ok;
    }
    return out;
  }
  static List<String> pendingDefective() {
    List<String> ids = new ArrayList<>();
    for (int pg = 0; pg < 400; pg++) {
      Res r = get("/api/v1/pos/stock/defective?status=PENDING_REVIEW&size=50&page=" + pg, adm());
      List<String> l = strs(r.body(), "id");
      if (l.isEmpty()) break;
      ids.addAll(l);
    }
    return ids;
  }
  static List<Object> offlineExceptions(int n) {
    Set<String> before = new HashSet<>(openExceptions(Integer.MAX_VALUE));
    // a zero-stock product: every offline bill for it is recorded and flagged OVERSOLD
    Res pr = post("/api/v1/admin/products", adm(), "{\"categoryId\":\"" + cats.get(0) + "\",\"nameEn\":\"Oversold bench\",\"nameGu\":\"x\",\"mrpPaise\":5000,\"pricePaise\":5000,\"unit\":\"pack\",\"qtyOnHand\":0,\"lowThreshold\":0}");
    String pid = str(pr.body(), "id");
    String soldAt = iso(Instant.now().minusSeconds(120));
    int calls = Math.max(1, n / 25 + 1);
    IntStream_range(calls, i -> {
      StringBuilder b = new StringBuilder();
      for (int k = 0; k < 25; k++) { if (k > 0) b.append(","); b.append("{\"idempotencyKey\":\"" + uuid() + "\",\"soldAt\":\"" + soldAt + "\",\"cart\":" + cartJson(pid, 1) + ",\"payments\":[{\"mode\":\"CASH\",\"amountPaise\":5000}]}"); }
      Res r = post("/api/v1/pos/sync/sales", csh(), "{\"sales\":[" + b + "]}");
      if (r.status() != 200) System.out.println("  prep: sync failed " + r.status() + " " + trunc(r.body(), 160));
    });
    List<Object> out = new ArrayList<>();
    for (String id : openExceptions(n + before.size() + 100)) if (!before.contains(id)) out.add(id);
    if (out.isEmpty()) System.out.println("  prep: no exceptions were created");
    return out;
  }
  static void IntStream_range(int n, java.util.function.IntConsumer f) {
    java.util.stream.IntStream.range(0, n).parallel().forEach(f);
  }
  static List<String> openExceptions(int max) {
    List<String> ids = new ArrayList<>();
    for (int pg = 0; pg < 400 && ids.size() < max; pg++) {
      List<String> l = strs(get("/api/v1/admin/pos/offline-exceptions?status=OPEN&size=50&page=" + pg, adm()).body(), "id");
      if (l.isEmpty()) break;
      ids.addAll(l);
    }
    return ids;
  }


  // ------------------------------------------------------------------ cleanup of benchmark-created rows
  static final String PURGE_FN = "CREATE OR REPLACE FUNCTION pg_temp.purge_ids(tbl regclass, ids uuid[]) RETURNS void AS $f$ DECLARE r record; sub uuid[]; BEGIN "
      + "IF ids IS NULL OR array_length(ids,1) IS NULL THEN RETURN; END IF; "
      + "FOR r IN SELECT c.conrelid::regclass AS child, a.attname AS col FROM pg_constraint c JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=c.conkey[1] WHERE c.contype='f' AND c.confrelid=tbl AND array_length(c.conkey,1)=1 LOOP "
      + "IF EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid=r.child AND attname='id') AND EXISTS (SELECT 1 FROM pg_constraint x WHERE x.contype='f' AND x.confrelid=r.child) THEN "
      + "EXECUTE format('SELECT array_agg(id) FROM %s WHERE %I = ANY($1)', r.child, r.col) INTO sub USING ids; PERFORM pg_temp.purge_ids(r.child, sub); END IF; "
      + "EXECUTE format('DELETE FROM %s WHERE %I = ANY($1)', r.child, r.col) USING ids; END LOOP; "
      + "EXECUTE format('DELETE FROM %s WHERE id = ANY($1)', tbl) USING ids; END $f$ LANGUAGE plpgsql;\n"
      + "CREATE OR REPLACE FUNCTION pg_temp.purge(tbl regclass, cond text) RETURNS void AS $f$ DECLARE ids uuid[]; BEGIN "
      + "EXECUTE format('SELECT array_agg(id) FROM %s WHERE %s', tbl, cond) INTO ids; PERFORM pg_temp.purge_ids(tbl, ids); END $f$ LANGUAGE plpgsql;\n";
  static boolean ALLFIX;
  static synchronized void cleanupAll() { ALLFIX = true; try { cleanup("product", "category", "banner", "unit", "coupon", "coin", "flash", "users", "address"); } finally { ALLFIX = false; } }
  static synchronized void cleanup(String... kinds) {
    StringBuilder q = new StringBuilder("SET session_replication_role = replica;\n" + PURGE_FN);
    for (String k : kinds) switch (k) {
      case "product" -> q.append("select pg_temp.purge('product', $$name_en LIKE 'Bench%' OR name_en='Oversold bench' OR (name_en LIKE 'Fixture base%' AND variant_name <> '')" + (ALLFIX ? " OR name_en LIKE 'Fixture%'" : "") + "$$);\n");
      case "category" -> q.append("select pg_temp.purge('category', $$name_en LIKE 'Bench cat%'" + (ALLFIX ? " OR name_en LIKE 'Fixture cat%'" : "") + "$$);\n");
      case "banner" -> q.append("select pg_temp.purge('hot_banner', $$title LIKE 'Bench %' OR title LIKE 'Tmp %'" + (ALLFIX ? " OR title LIKE 'Fixture %'" : "") + "$$);\n");
      case "unit" -> q.append("delete from unit_of_measure where name_en in ('Bench','Tmp') or name_en like 'Bench %'" + (ALLFIX ? " or name_en like 'Fixture%'" : "") + ";\n");
      case "coupon" -> q.append("select pg_temp.purge('coupon', $$code ~ '^(BC|TMP)[0-9A-F]{8}'"+(ALLFIX?" OR code ~ '^(FXC|BNC)'":"")+"$$);\n");
      case "coin" -> q.append("select pg_temp.purge('coin_rule', $$name LIKE 'Bench %'" + (ALLFIX ? " OR name LIKE 'Fixture %'" : "") + "$$);\n");
      case "flash" -> q.append("select pg_temp.purge('flash_sale', $$name LIKE 'Bench %' OR name LIKE 'Copy %'" + (ALLFIX ? " OR name LIKE 'Fixture %'" : "") + "$$);\n");
      case "users" -> q.append("select pg_temp.purge('users', $$email LIKE 'benchstaff-%' OR email LIKE 'benchcash-%'" + (ALLFIX ? " OR email LIKE 'fxstaff-%'" : "") + "$$);\n");
      case "address" -> q.append("delete from address where user_id in (select id from users where email like 'perf-%') and id not in (" + (wc.isEmpty() ? "'00000000-0000-0000-0000-000000000000'" : String.join(",", wc.stream().map(c -> "'" + c.addr + "'").toList())) + ");\n"
          + "delete from device_token where fcm_token like 'bench-%';\n");
      default -> { }
    }
    try {
      Process pr = new ProcessBuilder("docker", "exec", "-i", DB, "psql", "-U", "mall", "-d", "mall", "-q").redirectErrorStream(true).start();
      pr.getOutputStream().write(q.toString().getBytes(StandardCharsets.UTF_8)); pr.getOutputStream().close();
      String out = new String(pr.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      pr.waitFor();
      for (String ln : out.split("\n")) if (ln.contains("ERROR")) System.out.println("  cleanup " + String.join(",", kinds) + ": " + trunc(ln, 300));
    } catch (Exception e) { System.out.println("  cleanup failed: " + e); }
  }
  static String[] cleanupKinds(C c) {
    if (c.method.equals("GET")) return new String[0];
    String p = c.path;
    List<String> k = new ArrayList<>();
    if (p.contains("/products") || p.contains("/offline-exceptions")) k.add("product");
    if (p.contains("/categories")) k.add("category");
    if (p.contains("/hot-banners")) k.add("banner");
    if (p.contains("/units")) k.add("unit");
    if (p.contains("/coupons")) k.add("coupon");
    if (p.contains("/coin-rules")) k.add("coin");
    if (p.contains("/flash-sales")) k.add("flash");
    if (p.contains("/staff") || p.contains("/shifts/")) k.add("users");
    if (p.contains("/me/addresses")) k.add("address");
    if (p.contains("/me/devices")) k.add("address");
    return k.toArray(new String[0]);
  }
  static String tableCounts() {
    List<String> l = sql("select 'coin_rule='||(select count(*) from coin_rule)||' coupon='||(select count(*) from coupon)||' hot_banner='||(select count(*) from hot_banner)||' category='||(select count(*) from category)||' unit='||(select count(*) from unit_of_measure)||' flash_sale='||(select count(*) from flash_sale)||' product='||(select count(*) from product)||' address='||(select count(*) from address)||' users='||(select count(*) from users)");
    return l.isEmpty() ? "" : l.get(0);
  }

  // ------------------------------------------------------------------ runner
  static volatile C CURRENT;

  record Stats(int n, double secs, double tps, double avg, double p50, double p90, double p95, double p99, double max, int errors, Map<Integer, Long> codes, String sample, boolean limited) {}

  static double pct(double[] s, double p) { if (s.length == 0) return 0; int i = (int) Math.ceil(p * s.length) - 1; return s[Math.max(0, Math.min(s.length - 1, i))]; }

  static List<Object> buildPool(C c, int n) {
    if (c.bulk != null) {
      try { return new ArrayList<>(c.bulk.apply(n)); } catch (Exception e) { System.out.println("  bulk prep failed: " + e); return new ArrayList<>(); }
    }
    ConcurrentLinkedQueue<Object> q = new ConcurrentLinkedQueue<>();
    AtomicInteger started = new AtomicInteger(), failed = new AtomicInteger();
    long deadline = System.currentTimeMillis() + 8 * 60_000L;
    Thread[] th = new Thread[USERS];
    for (int i = 0; i < th.length; i++) {
      th[i] = new Thread(() -> {
        while (System.currentTimeMillis() < deadline && started.getAndIncrement() < n) {
          try { Object o = c.prep.get(); if (o != null) q.add(o); else failed.incrementAndGet(); } catch (Exception e) { failed.incrementAndGet(); }
        }
      });
      th[i].start();
    }
    for (Thread t : th) try { t.join(9 * 60_000L); } catch (InterruptedException e) { }
    if (failed.get() > 0) System.out.println("  pool: " + failed.get() + " prep failures");
    return new ArrayList<>(q);
  }

  static Stats runPhase(C c, int users, int secs, List<Object> pool) throws Exception {
    ConcurrentLinkedQueue<Object> q = pool == null ? null : new ConcurrentLinkedQueue<>(pool);
    final long start = System.nanoTime(), end = start + secs * 1_000_000_000L;
    ConcurrentHashMap<Integer, LongAdder> codes = new ConcurrentHashMap<>();
    AtomicReference<String> sample = new AtomicReference<>();
    AtomicInteger errors = new AtomicInteger();
    AtomicLong seq = new AtomicLong();
    AtomicLong lastEnd = new AtomicLong(start);
    AtomicBoolean_ exhausted = new AtomicBoolean_();
    double[][] lat = new double[users][];
    Thread[] th = new Thread[users];
    for (int u = 0; u < users; u++) {
      final int vu = u;
      th[u] = new Thread(() -> {
        double[] buf = new double[4096]; int cnt = 0;
        while (System.nanoTime() < end) {
          Object target = null;
          if (q != null) { target = q.poll(); if (target == null) { exhausted.v = true; break; } }
          Res res;
          try {
            Rq rq = c.rq.make(vu, seq.incrementAndGet(), target);
            res = send(rq);
          } catch (Throwable e) { res = new Res(0, "harness: " + e, 0); }
          long now = System.nanoTime();
          lastEnd.accumulateAndGet(now, Math::max);
          boolean good = c.ok.contains(res.status());
          codes.computeIfAbsent(res.status(), k -> new LongAdder()).increment();
          if (!good) { errors.incrementAndGet(); sample.compareAndSet(null, res.status() + " " + trunc(res.body(), 220)); }
          if (res.nanos() > 0 || good) { if (cnt == buf.length) buf = Arrays.copyOf(buf, cnt * 2); buf[cnt++] = res.ms(); }
          if (c.hook != null) try { c.hook.on(vu, target, res); } catch (Throwable e) { }
        }
        lat[vu] = Arrays.copyOf(buf, cnt);
      });
      th[u].setDaemon(true);
      th[u].start();
    }
    boolean hung = false;
    long hardStop = System.currentTimeMillis() + (secs + 60) * 1000L;
    for (Thread t : th) { long left = hardStop - System.currentTimeMillis(); t.join(Math.max(1, left)); if (t.isAlive()) { hung = true; t.interrupt(); } }
    int total = 0; for (double[] l : lat) if (l != null) total += l.length;
    double[] all = new double[total]; int p = 0;
    for (double[] l : lat) if (l != null) { System.arraycopy(l, 0, all, p, l.length); p += l.length; }
    Arrays.sort(all);
    double wall = Math.max(0.001, (lastEnd.get() - start) / 1e9);
    if (!exhausted.v && !hung) wall = Math.max(wall, secs);   // whole window used
    double sum = 0; for (double d : all) sum += d;
    Map<Integer, Long> cm = new TreeMap<>(); codes.forEach((k, v) -> cm.put(k, v.sum()));
    String smp = sample.get(); if (hung) smp = "HUNG: " + (smp == null ? "" : smp);
    long okCount = 0;
    int n = total;
    return new Stats(n, wall, n / wall, n == 0 ? 0 : sum / n, pct(all, .50), pct(all, .90), pct(all, .95), pct(all, .99), all.length == 0 ? 0 : all[all.length - 1], errors.get(), cm, smp, exhausted.v || hung);
  }
  static class AtomicBoolean_ { volatile boolean v; }

  static String codesStr(Map<Integer, Long> m) { StringBuilder b = new StringBuilder(); m.forEach((k, v) -> { if (b.length() > 0) b.append(' '); b.append(k == 0 ? "EXC" : k).append('x').append(v); }); return b.toString(); }
  static Map<Integer, Long> merge(Map<Integer, Long> a, Map<Integer, Long> b) { Map<Integer, Long> m = new TreeMap<>(a); b.forEach((k, v) -> m.merge(k, v, Long::sum)); return m; }

  static String[] COLS = {"id", "group", "method", "path", "who", "note", "state", "a_n", "a_p50", "a_p95", "a_p99", "a_errors", "b_n", "b_secs", "b_tps", "b_avg", "b_p50", "b_p90", "b_p95", "b_p99", "b_max", "b_errors", "b_err_pct", "codes", "sample_error", "a_limited", "b_limited", "pool_note", "tables"};

  static Map<String, String> runCase(C c) throws Exception {
    CURRENT = c; c.state.clear();
    Map<String, String> row = new LinkedHashMap<>();
    row.put("id", c.id()); row.put("group", c.group); row.put("method", c.method); row.put("path", c.path); row.put("who", c.who); row.put("note", c.note);
    ensureAuth(false);
    row.put("tables", tableCounts());
    StringBuilder poolNote = new StringBuilder();
    // warm-up
    List<Object> pw = c.pooled() ? buildPool(c, sc(c.poolW)) : null;
    runPhase(c, 4, WARM, pw);
    Thread.sleep(300);
    // run A
    ensureAuth(false);
    List<Object> pa = c.pooled() ? buildPool(c, sc(c.poolA)) : null;
    if (pa != null) poolNote.append("A pool ").append(pa.size()).append(' ');
    Stats a = runPhase(c, 1, A_SEC, pa);
    Thread.sleep(300);
    // run B
    ensureAuth(false);
    List<Object> pb = c.pooled() ? buildPool(c, sc(c.poolB)) : null;
    if (pb != null) poolNote.append("B pool ").append(pb.size());
    Stats b = runPhase(c, USERS, B_SEC, pb);
    if (c.after != null) try { c.after.run(); } catch (Exception e) { System.out.println("  after-hook failed: " + e); }
    String[] ck = cleanupKinds(c); if (ck.length > 0) cleanup(ck);
    int errB = b.errors();
    row.put("state", (a.n() == 0 && b.n() == 0) ? "NO_DATA" : "OK");
    row.put("a_n", "" + a.n()); row.put("a_p50", f(a.p50())); row.put("a_p95", f(a.p95())); row.put("a_p99", f(a.p99())); row.put("a_errors", "" + a.errors());
    row.put("b_n", "" + b.n()); row.put("b_secs", f(b.secs())); row.put("b_tps", f(b.tps())); row.put("b_avg", f(b.avg())); row.put("b_p50", f(b.p50())); row.put("b_p90", f(b.p90()));
    row.put("b_p95", f(b.p95())); row.put("b_p99", f(b.p99())); row.put("b_max", f(b.max())); row.put("b_errors", "" + errB);
    row.put("b_err_pct", f(b.n() == 0 ? 0 : 100.0 * errB / b.n()));
    row.put("codes", codesStr(merge(a.codes(), b.codes())));
    String smp = b.sample() != null ? b.sample() : a.sample();
    row.put("sample_error", smp == null ? "" : smp);
    row.put("a_limited", "" + a.limited()); row.put("b_limited", "" + b.limited()); row.put("pool_note", poolNote.toString().trim());
    return row;
  }
  static int sc(int n) { return Math.max(3, (int) (n * Double.parseDouble(System.getProperty("poolscale", "1")))); }
  static String f(double d) { return String.format(Locale.ROOT, "%.2f", d); }

  static synchronized void save(Map<String, String> row) throws Exception {
    Path p = Path.of(OUT);
    LinkedHashMap<String, String> lines = new LinkedHashMap<>();
    if (Files.exists(p)) for (String l : Files.readAllLines(p)) { if (l.startsWith("id\t")) continue; int i = l.indexOf('\t'); if (i > 0) lines.put(l.substring(0, i), l); }
    StringBuilder sb = new StringBuilder();
    for (String col : COLS) { if (sb.length() > 0) sb.append('\t'); sb.append(trunc(row.getOrDefault(col, ""), 260)); }
    lines.put(row.get("id"), sb.toString());
    List<String> out = new ArrayList<>(); out.add(String.join("\t", COLS)); out.addAll(lines.values());
    Files.write(p, out);
  }

  public static void main(String[] args) throws Exception {
    System.setProperty("java.awt.headless", "true");
    base = args.length > 0 ? args[0] : "http://localhost:8080";
    String filter = args.length > 1 ? args[1] : "";
    defineCases();
    List<C> sel = new ArrayList<>();
    Set<String> only = System.getProperty("idsfile") == null ? null : new HashSet<>(Files.readAllLines(Path.of(System.getProperty("idsfile"))));
    for (C c : CASES) {
      if (only != null) { if (only.contains(c.id())) sel.add(c); continue; }
      if (filter.isBlank()) { sel.add(c); continue; }
      for (String f : filter.split(",")) if (!f.isBlank() && (c.id() + " " + c.group).toLowerCase().contains(f.trim().toLowerCase())) { sel.add(c); break; }
    }
    System.out.println("cases defined=" + CASES.size() + " selected=" + sel.size() + " out=" + OUT);
    if (args.length > 2 && args[2].equals("cleanup")) { cleanupAll(); System.out.println("cleanup done"); System.exit(0); }
    if (args.length > 2 && args[2].equals("list")) { for (C c : CASES) System.out.println(c.group + " | " + c.id()); return; }
    cleanupAll();
    setupWorld();
    int i = 0;
    for (C c : sel) {
      i++;
      long t0 = System.currentTimeMillis();
      System.out.printf("[%d/%d] %s %s %s%n", i, sel.size(), c.group, c.id(), c.note);
      Map<String, String> row;
      try {
        row = runCase(c);
      } catch (Throwable e) {
        row = new LinkedHashMap<>(); row.put("id", c.id()); row.put("group", c.group); row.put("method", c.method); row.put("path", c.path); row.put("who", c.who); row.put("note", c.note);
        row.put("state", "HARNESS_FAILURE"); row.put("sample_error", String.valueOf(e));
        System.out.println("  FAILED: " + e);
        e.printStackTrace(System.out);
      }
      save(row);
      System.out.printf("   A n=%s p50=%s p95=%s err=%s | B n=%s tps=%s p50=%s p95=%s p99=%s max=%s err=%s | %s%s  (%.0fs)%n", row.get("a_n"), row.get("a_p50"), row.get("a_p95"), row.get("a_errors"), row.get("b_n"), row.get("b_tps"),
          row.get("b_p50"), row.get("b_p95"), row.get("b_p99"), row.get("b_max"), row.get("b_errors"), row.get("codes"), row.getOrDefault("sample_error", "").isEmpty() ? "" : "  sample: " + row.get("sample_error"), (System.currentTimeMillis() - t0) / 1000.0);
    }
    cleanupAll();
    System.out.println("== done");
    System.exit(0);
  }
}
