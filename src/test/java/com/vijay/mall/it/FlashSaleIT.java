package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.FlashPricing;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.flash.app.FlashLifecycle;
import com.vijay.mall.support.FlashTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Flash sales against real PostgreSQL: set-up rules, prices everywhere, quantity limits, give-back, reports, lifecycle, concurrency. */
@SuppressWarnings({"unchecked", "rawtypes"})
class FlashSaleIT extends FlashTestBase {
  @Autowired FlashLifecycle lifecycle;
  @Autowired FlashPricing pricing;
  @Autowired TransactionTemplate tx;
  @Autowired io.micrometer.core.instrument.MeterRegistry meters;

  private static final String ADMIN_API = "/api/v1/admin/flash-sales";

  private void endNowViaDb(Object saleId) {
    jdbc.update("update flash_sale set starts_at = now() - interval '2 hours', ends_at = now() - interval '1 minute' where id = ?::uuid", saleId.toString());
    caches.invalidate(AppCaches.Name.FLASH);
  }

  // ---------- configuration ----------
  @Test
  void aScheduledSaleIsNotActiveUntilStartedNow() {
    String admin = admin();
    Map<String, Object> product = newProduct(10000, 20);
    Object pid = product.get("id");
    Instant start = Instant.now().plusSeconds(3600);
    Map<String, Object> created = createSale(saleBody(List.of(item(pid, 5)), Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(7200).toString())));
    Object saleId = created.get("id");
    assertThat(created.get("status")).isEqualTo("SCHEDULED");

    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody()).get("flash")).as("not running yet").isNull();
    assertThat(quote(token(freshEmail()), pid, 1).get("subtotalPaise")).isEqualTo(10000);
    assertThat(shopItems("/api/v1/flash-sales/active")).noneMatch(i -> pid.equals(i.get("productId")));
    List<Map<String, Object>> upcoming = shopItems("/api/v1/flash-sales/upcoming");
    assertThat(upcoming).anySatisfy(i -> {
      assertThat(i.get("productId")).isEqualTo(pid);
      assertThat(i.get("salePricePaise")).isEqualTo(8000);
      assertThat(i.get("listPricePaise")).isEqualTo(10000);
    });

    var started = post(ADMIN_API + "/" + saleId + "/start-now", admin, null);
    assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(started.getBody().get("status")).isEqualTo("ACTIVE");
    caches.invalidate(AppCaches.Name.FLASH);
    Map flash = (Map) get("/api/v1/products/" + pid, null).getBody().get("flash");
    assertThat(flash.get("salePricePaise")).isEqualTo(8000);
    assertThat(flash.get("listPricePaise")).isEqualTo(10000);
    assertThat(flash.get("discountPercent")).isEqualTo(20);
    assertThat(flash.get("unitsLeft")).isEqualTo(5);
    assertThat(flash.get("endsAt")).isNotNull();
    assertThat(post(ADMIN_API + "/" + saleId + "/start-now", admin, null).getStatusCode()).as("already running").isEqualTo(HttpStatus.CONFLICT);
    cancelSale(saleId);
  }

  @Test
  void theShopListDetailAndVariantsShowTheSalePrice() {
    Map<String, Object> product = newProduct(5000, 20);
    Object pid = product.get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    List<Map> items = (List<Map>) get("/api/v1/products?size=50&q=" + product.get("nameEn").toString().substring(11), null).getBody().get("items");
    assertThat(items).hasSize(1);
    assertThat(((Map) items.get(0).get("flash")).get("salePricePaise")).isEqualTo(4000);
    assertThat(items.get(0).get("pricePaise")).as("the normal price stays visible").isEqualTo(5000);
    assertThat(((Map) items.get(0).get("flash")).get("unitsLeft")).as("no cap").isNull();

    Map group = get("/api/v1/product-groups/" + pid, null).getBody();
    assertThat(((Map) group.get("flash")).get("salePricePaise")).isEqualTo(4000);
    assertThat(((Map) ((List<Map>) group.get("variants")).get(0).get("flash")).get("salePricePaise")).isEqualTo(4000);
    var groups = (List<Map>) get("/api/v1/product-groups?size=50&q=" + product.get("nameEn").toString().substring(11), null).getBody().get("items");
    assertThat(((Map) groups.get(0).get("flash")).get("discountPercent")).isEqualTo(20);

    Map active = get("/api/v1/flash-sales/active", null).getBody();
    assertThat(active.get("now")).isNotNull();
    Map oursSale = ((List<Map>) active.get("sales")).stream().filter(s -> sale.get("id").equals(s.get("id"))).findFirst().orElseThrow();
    assertThat(((Number) oursSale.get("secondsToEnd")).longValue()).isBetween(3000L, 3600L);
    endNowViaDb(sale.get("id"));
    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody()).get("flash")).isNull();
  }

  @Test
  void staffCanConfigureAndCustomersCannot() {
    Object pid = newProduct(10000, 10).get("id");
    Map<String, Object> body = saleBody(List.of(item(pid, 3)), Map.of());
    assertThat(post(ADMIN_API, token(freshEmail()), body).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(post(ADMIN_API, null, body).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get(ADMIN_API, token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get(ADMIN_API + "/report", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    var res = post(ADMIN_API, cashier(), body);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Object id = res.getBody().get("id");
    assertThat(get(ADMIN_API + "/" + id, cashier()).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post(ADMIN_API + "/" + id + "/cancel", cashier(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    // the cashier still cannot reach the rest of /admin
    assertThat(get("/api/v1/admin/dashboard", cashier()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    // public endpoints need no sign-in
    assertThat(get("/api/v1/flash-sales/active", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/flash-sales/upcoming", null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void overlappingSalesForTheSameProductAreRefusedAndNameTheOtherSale() {
    Object pid = newProduct(10000, 10).get("id");
    Instant t0 = Instant.now().plusSeconds(3600);
    Map<String, Object> first = createSale(saleBody(List.of(item(pid, null)), Map.of("name", "First sale", "startsAt", t0.toString(),
        "endsAt", t0.plusSeconds(3600).toString())));
    var clash = post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("startsAt", t0.plusSeconds(1800).toString(),
        "endsAt", t0.plusSeconds(5400).toString())));
    assertThat(clash.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(clash.getBody().get("code")).isEqualTo("FLASH_OVERLAP");
    assertThat(clash.getBody().get("message").toString()).contains("First sale");
    assertThat(((Map) clash.getBody().get("details")).get("saleId")).isEqualTo(first.get("id"));
    // right after it is fine
    Map<String, Object> later = createSale(saleBody(List.of(item(pid, null)), Map.of("startsAt", t0.plusSeconds(3600).toString(),
        "endsAt", t0.plusSeconds(7200).toString())));
    // moving the later one back into the first is refused too
    var move = call(HttpMethod.PATCH, ADMIN_API + "/" + later.get("id"), admin(), Map.of("startsAt", t0.plusSeconds(60).toString()), null);
    assertThat(move.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    // a cancelled sale frees the product
    cancelSale(first.get("id"));
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + later.get("id"), admin(), Map.of("startsAt", t0.plusSeconds(60).toString()), null)
        .getStatusCode()).isEqualTo(HttpStatus.OK);
    // adding the product to another sale at that time hits the same rule
    Object other = newProduct(10000, 10).get("id");
    Map<String, Object> third = createSale(saleBody(List.of(item(other, null)), Map.of("startsAt", t0.toString(), "endsAt", t0.plusSeconds(600).toString())));
    var add = post(ADMIN_API + "/" + third.get("id") + "/items", admin(), item(pid, null));
    assertThat(add.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    cancelSale(later.get("id"));
    cancelSale(third.get("id"));
    assertThat(post(ADMIN_API + "/" + third.get("id") + "/items", admin(), item(pid, null)).getStatusCode()).as("cancelled sales cannot change").isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void inputIsValidated() {
    Object pid = newProduct(10000, 10).get("id");
    Object cheap = newProduct(100, 10).get("id");
    Map<String, Object> plain = Map.of();
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("discountValue", 0))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("discountValue", 91))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("discountKind", "FIXED_PRICE", "discountValue", 99)))
        .getStatusCode()).as("below Rs 1").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("discountKind", "FIXED_PRICE", "discountValue", 10000)))
        .getStatusCode()).as("not below the list price").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("discountKind", "HALF"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("discountKind", "NONE"))).getStatusCode())
        .as("no default and no item discount").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(cheap, null)), plain)).getStatusCode()).as("Rs 1 product cannot go lower").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("name", ""))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("name", "x".repeat(81)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("description", "x".repeat(301)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("bannerText", "x".repeat(81)))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("channel", "TV"))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("perCustomerLimit", -1))).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(), plain)).getStatusCode()).as("no items").isEqualTo(HttpStatus.BAD_REQUEST);
    List<Map<String, Object>> many = new ArrayList<>();
    for (int i = 0; i < 201; i++) many.add(item(UUID.randomUUID(), null));
    assertThat(post(ADMIN_API, admin(), saleBody(many, plain)).getStatusCode()).as("at most 200 items").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(UUID.randomUUID(), null)), plain)).getStatusCode()).as("unknown product").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null), item(pid, null)), plain)).getStatusCode()).as("same product twice").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, 0)), plain)).getStatusCode()).as("cap of zero").isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> badLimit = item(pid, null);
    badLimit.put("perCustomerLimit", 1000);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(badLimit), plain)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> noProduct = new HashMap<>();
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(noProduct), plain)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> badKind = item(pid, null);
    badKind.put("discountKind", "PERCENT");
    badKind.put("discountValue", 95);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(badKind), plain)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // times
    Instant now = Instant.now();
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("endsAt", now.plus(Duration.ofDays(31)).toString()))).getStatusCode())
        .as("longer than 30 days").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("startsAt", now.plusSeconds(7200).toString(),
        "endsAt", now.plusSeconds(3600).toString()))).getStatusCode()).as("ends before it starts").isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API, admin(), saleBody(List.of(item(pid, null)), Map.of("startsAt", now.minus(Duration.ofHours(2)).toString()))).getStatusCode())
        .as("starts in the past").isEqualTo(HttpStatus.BAD_REQUEST);
    Map<String, Object> noEnd = saleBody(List.of(item(pid, null)), plain);
    noEnd.remove("endsAt");
    assertThat(post(ADMIN_API, admin(), noEnd).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    // a list gets validated too
    assertThat(get(ADMIN_API + "?status=BOGUS", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get(ADMIN_API + "/" + UUID.randomUUID(), admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get(ADMIN_API + "/" + UUID.randomUUID() + "/report", admin()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(get(ADMIN_API + "/report?from=2026-02-01&to=2026-01-01", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get(ADMIN_API + "/report?from=2020-01-01&to=2026-01-01", admin()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void anItemOverrideBeatsTheSaleDefaultAndAnItemNeedsADiscountFromSomewhere() {
    Object a = newProduct(10000, 10).get("id");
    Object b = newProduct(10000, 10).get("id");
    Object c = newProduct(10000, 10).get("id");
    Map<String, Object> itemB = item(b, null);
    itemB.put("discountKind", "FIXED_PRICE");
    itemB.put("discountValue", 6500);
    Map<String, Object> itemC = item(c, null);
    itemC.put("discountKind", "PERCENT");
    itemC.put("discountValue", 50);
    Map<String, Object> sale = createSale(saleBody(List.of(item(a, null), itemB, itemC), Map.of("discountValue", 10)));
    assertThat(saleItem(sale.get("id"), a).get("salePricePaise")).isEqualTo(9000);
    assertThat(saleItem(sale.get("id"), b).get("salePricePaise")).isEqualTo(6500);
    assertThat(saleItem(sale.get("id"), c).get("salePricePaise")).isEqualTo(5000);
    assertThat(saleItem(sale.get("id"), c).get("effectiveKind")).isEqualTo("PERCENT");
    assertThat(saleItem(sale.get("id"), a).get("discountKind")).as("follows the sale").isNull();
    // a sale without a default needs every item to carry its own discount
    Object d = newProduct(10000, 10).get("id");
    Map<String, Object> own = item(d, null);
    own.put("discountKind", "PERCENT");
    own.put("discountValue", 30);
    Map<String, Object> noDefault = createSale(saleBody(List.of(own), Map.of("discountKind", "NONE", "startsAt", Instant.now().plusSeconds(86400).toString(),
        "endsAt", Instant.now().plusSeconds(90000).toString())));
    assertThat(saleItem(noDefault.get("id"), d).get("salePricePaise")).isEqualTo(7000);
    // removing the default while an item relies on it is refused
    var drop = call(HttpMethod.PATCH, ADMIN_API + "/" + noDefault.get("id"), admin(), Map.of("discountKind", "NONE"), null);
    assertThat(drop.getStatusCode()).isEqualTo(HttpStatus.OK);
    var dropMain = call(HttpMethod.PATCH, ADMIN_API + "/" + sale.get("id"), admin(), Map.of("discountKind", "NONE"), null);
    assertThat(dropMain.getStatusCode()).as("running sale cannot change its discount").isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> reliant = createSale(saleBody(List.of(item(newProduct(10000, 5).get("id"), null)), Map.of("startsAt", Instant.now().plusSeconds(86400).toString(),
        "endsAt", Instant.now().plusSeconds(90000).toString())));
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + reliant.get("id"), admin(), Map.of("discountKind", "NONE"), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    for (Object id : List.of(sale.get("id"), noDefault.get("id"), reliant.get("id"))) cancelSale(id);
  }

  @Test
  void theSalePriceFollowsALaterListPriceChange() {
    Map<String, Object> product = newProduct(10000, 10);
    Object pid = product.get("id");
    createRunning(pid, null, Map.of());
    String cust = token(freshEmail());
    assertThat(quote(cust, pid, 1).get("subtotalPaise")).isEqualTo(8000);
    Map<String, Object> edit = new HashMap<>(Map.of("categoryId", category(), "nameEn", product.get("nameEn"), "nameGu", "નામ", "mrpPaise", 12000,
        "pricePaise", 12000, "unit", "pack", "packSize", "1 pc", "qtyOnHand", 10));
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/products/" + pid, admin(), edit, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(quote(cust, pid, 1).get("subtotalPaise")).as("20% off the new list price").isEqualTo(9600);
    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody().get("flash")).get("salePricePaise")).isEqualTo(9600);
  }

  // ---------- pricing everywhere ----------
  @Test
  void quoteOrderAndCounterBillAllChargeTheSalePrice() {
    Map<String, Object> product = newProduct(10000, 20);
    Object pid = product.get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    String cust = token(freshEmail());

    Map q = quote(cust, pid, 2);
    Map line = ((List<Map>) q.get("lines")).get(0);
    assertThat(line.get("unitPricePaise")).isEqualTo(8000);
    assertThat(line.get("lineTotalPaise")).isEqualTo(16000);
    assertThat(line.get("flashSaleId")).isEqualTo(sale.get("id"));
    assertThat(line.get("listPricePaise")).isEqualTo(10000);
    assertThat(line.get("flashDiscountPaise")).isEqualTo(4000);
    assertThat(q.get("totalPaise")).isEqualTo(16000);

    String orderId = order(cust, pid, 2);
    Map placed = get("/api/v1/orders/" + orderId, cust).getBody();
    assertThat(placed.get("totalPaise")).isEqualTo(16000);
    assertThat(((Map) ((List<Map>) placed.get("items")).get(0)).get("unitPricePaise")).as("the price paid is frozen on the order").isEqualTo(8000);
    // a later change of the sale does not touch the order
    cancelSale(sale.get("id"));
    assertThat(((Map) ((List<Map>) get("/api/v1/orders/" + orderId, cust).getBody().get("items")).get(0)).get("unitPricePaise")).isEqualTo(8000);

    // the counter
    Map<String, Object> sale2 = createRunning(pid, null, Map.of("discountValue", 25));
    Map pq = posQuote(pid, 3);
    assertThat(pq.get("totalPaise")).isEqualTo(22500);
    var billed = posSale(pid, 3, 22500, null);
    assertThat(billed.getStatusCode()).as(String.valueOf(billed.getBody())).isEqualTo(HttpStatus.CREATED);
    assertThat(billed.getBody().get("totalPaise")).isEqualTo(22500);
    assertThat(((Map) ((List<Map>) billed.getBody().get("items")).get(0)).get("unitPricePaise")).isEqualTo(7500);
    assertThat(liveConsumed(sale2.get("id"))).isEqualTo(3);
    assertThat(jdbc.queryForObject("select channel from flash_consumption where flash_sale_id = ?::uuid", String.class, sale2.get("id").toString())).isEqualTo("POS");
    cancelSale(sale2.get("id"));
  }

  @Test
  void couponsAndCoinsApplyOnTopOfTheSalePrice() {
    Object pid = newProduct(10000, 20).get("id");
    createRunning(pid, null, Map.of());
    String code = "FLASH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    var coupon = post("/api/v1/admin/coupons", admin(), Map.of("code", code, "kind", "PERCENT", "value", 10));
    assertThat(coupon.getStatusCode()).as(String.valueOf(coupon.getBody())).isEqualTo(HttpStatus.CREATED);
    var res = post("/api/v1/cart/quote", token(freshEmail()), Map.of("items", List.of(Map.of("productId", pid, "qty", 1)), "couponCode", code,
        "coinMode", "NONE", "fulfilment", "PICKUP"));
    assertThat(res.getBody().get("subtotalPaise")).isEqualTo(8000);
    assertThat(res.getBody().get("couponDiscountPaise")).as("10% of the sale price").isEqualTo(800);
    assertThat(res.getBody().get("totalPaise")).isEqualTo(7200);
  }

  // ---------- quantity limits ----------
  @Test
  void aCapSplitsTheNextBuyerBetweenSaleAndListPriceAndSoldOutItemsSellNormally() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, 3, Map.of());
    Object itemId = saleItem(sale.get("id"), pid).get("id");
    String a = token(freshEmail());
    String b = token(freshEmail());
    String c = token(freshEmail());

    order(a, pid, 2);
    assertThat(soldQty(itemId)).isEqualTo(2);
    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody().get("flash")).get("unitsLeft")).as("never served from the cache").isEqualTo(1);

    Map q = quote(b, pid, 3);
    List<Map> lines = (List<Map>) q.get("lines");
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).get("qty")).isEqualTo(1);
    assertThat(lines.get(0).get("unitPricePaise")).isEqualTo(8000);
    assertThat(lines.get(1).get("qty")).isEqualTo(2);
    assertThat(lines.get(1).get("unitPricePaise")).isEqualTo(10000);
    assertThat(lines.get(1).get("flashSaleId")).isNull();
    assertThat(q.get("subtotalPaise")).isEqualTo(8000 + 20000);
    assertThat(q.get("totalPaise")).isEqualTo(28000);
    String orderB = order(b, pid, 3);
    Map placed = get("/api/v1/orders/" + orderB, b).getBody();
    assertThat(((List<Map>) placed.get("items"))).hasSize(2);
    assertThat(placed.get("totalPaise")).isEqualTo(28000);
    assertThat(soldQty(itemId)).isEqualTo(3);

    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody()).get("flash")).as("sold out: normal price again").isNull();
    assertThat(quote(c, pid, 2).get("subtotalPaise")).isEqualTo(20000);
    assertThat(jdbc.queryForObject("select count(*) from flash_sale_item where sold_qty > coalesce(max_qty, 2147483647)", Long.class)).isZero();
    assertThat(shopItems("/api/v1/flash-sales/active")).anySatisfy(i -> {
      if (pid.equals(i.get("productId"))) assertThat(i.get("unitsLeft")).isEqualTo(0);
    });
    cancelSale(sale.get("id"));
  }

  @Test
  void aStaleQuoteIsAnsweredWithPriceChangedWhenSomeoneElseTookTheLastUnits() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, 1, Map.of());
    String a = token(freshEmail());
    String b = token(freshEmail());
    long staleTotal = ((Number) quote(a, pid, 1).get("totalPaise")).longValue();
    assertThat(staleTotal).isEqualTo(8000);
    order(b, pid, 1); // takes the last unit
    var res = placeOrder(a, pid, 1, staleTotal);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code")).isEqualTo("PRICE_CHANGED");
    Map fresh = (Map) ((Map) res.getBody().get("details")).get("quote");
    assertThat(fresh.get("totalPaise")).isEqualTo(10000);
    assertThat(soldQty(saleItem(sale.get("id"), pid).get("id"))).isEqualTo(1);
    assertThat(placeOrder(a, pid, 1, 10000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    cancelSale(sale.get("id"));
  }

  @Test
  void thePerCustomerLimitCountsPerProductAndPerCustomer() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of("perCustomerLimit", 2));
    String a = token(freshEmail());
    String b = token(freshEmail());
    order(a, pid, 2);
    Map q = quote(a, pid, 1);
    assertThat(q.get("subtotalPaise")).as("allowance used up: normal price").isEqualTo(10000);
    assertThat(((Map) ((List<Map>) q.get("lines")).get(0)).get("flashSaleId")).isNull();
    assertThat(quote(b, pid, 3).get("subtotalPaise")).as("another customer: 2 at the sale price, 1 normal").isEqualTo(16000 + 10000);
    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody().get("flash")).get("perCustomerLimit")).isEqualTo(2);
    // an item override replaces the sale limit; 0 means no limit
    post(ADMIN_API + "/" + sale.get("id") + "/items", admin(), item(newProduct(10000, 5).get("id"), null));
    Object itemId = saleItem(sale.get("id"), pid).get("id");
    Map<String, Object> raise = item(pid, null);
    raise.put("perCustomerLimit", 5);
    var upd = call(HttpMethod.PUT, ADMIN_API + "/" + sale.get("id") + "/items/" + itemId, admin(), raise, null);
    assertThat(upd.getStatusCode()).as("units were sold: only the cap may change").isEqualTo(HttpStatus.CONFLICT);
    cancelSale(sale.get("id"));
  }

  @Test
  void anItemLimitOverridesTheSaleLimit() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> own = item(pid, null);
    own.put("perCustomerLimit", 1);
    createSale(saleBody(List.of(own), Map.of("perCustomerLimit", 5)));
    String a = token(freshEmail());
    order(a, pid, 1);
    assertThat(quote(a, pid, 1).get("subtotalPaise")).isEqualTo(10000);
  }

  @Test
  void cancelledAndRejectedOrdersGiveTheirUnitsBackExactlyOnce() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, 4, Map.of());
    Object itemId = saleItem(sale.get("id"), pid).get("id");
    String cust = token(freshEmail());

    String first = order(cust, pid, 2);
    assertThat(soldQty(itemId)).isEqualTo(2);
    assertThat(post("/api/v1/orders/" + first + "/cancel", cust, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(soldQty(itemId)).as("given back").isZero();
    assertThat(jdbc.queryForObject("select count(*) from flash_consumption where order_id = ?::uuid and released_at is not null", Long.class, first)).isEqualTo(1);
    assertThat(post("/api/v1/orders/" + first + "/cancel", cust, null).getStatusCode()).as("a closed order cannot be cancelled again").isEqualTo(HttpStatus.CONFLICT);
    assertThat(soldQty(itemId)).as("not given back twice").isZero();

    String second = order(cust, pid, 4);
    assertThat(soldQty(itemId)).isEqualTo(4);
    post("/api/v1/admin/orders/" + second + "/status", admin(), Map.of("status", "CONFIRMED"));
    var reject = post("/api/v1/admin/orders/" + second + "/reject", admin(), Map.of("reason", "cannot deliver"));
    assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(soldQty(itemId)).isZero();
    assertThat(liveConsumed(sale.get("id"))).isZero();
    // the units can be bought again, at the sale price
    assertThat(quote(cust, pid, 4).get("subtotalPaise")).isEqualTo(32000);
    // an order that never used the sale gives nothing back
    cancelSale(sale.get("id"));
  }

  @Test
  void anEndedSaleReturnsNormalPricesEverywhere() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    String cust = token(freshEmail());
    assertThat(quote(cust, pid, 1).get("subtotalPaise")).isEqualTo(8000);
    long staleTotal = 8000;
    endNowViaDb(sale.get("id"));
    assertThat(sale(sale.get("id")).get("status")).isEqualTo("ENDED");
    assertThat(quote(cust, pid, 1).get("subtotalPaise")).isEqualTo(10000);
    assertThat(posQuote(pid, 1).get("totalPaise")).isEqualTo(10000);
    assertThat(((Map) get("/api/v1/products/" + pid, null).getBody()).get("flash")).isNull();
    var res = placeOrder(cust, pid, 1, staleTotal);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code")).isEqualTo("PRICE_CHANGED");
    assertThat(post(ADMIN_API + "/" + sale.get("id") + "/end-now", admin(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + sale.get("id"), admin(), Map.of("name", "late"), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(post(ADMIN_API + "/" + sale.get("id") + "/cancel", admin(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void aSaleThatEndsBetweenQuoteAndPlacementIsRefusedByTheDatabaseRule() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    String cust = token(freshEmail());
    long total = ((Number) quote(cust, pid, 1).get("totalPaise")).longValue();
    // the cache still holds the old definition (as it may for a few seconds), but the table says the sale is over
    jdbc.update("update flash_sale set starts_at = now() - interval '2 hours', ends_at = now() - interval '1 minute' where id = ?::uuid", sale.get("id").toString());
    var res = placeOrder(cust, pid, 1, total);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(res.getBody().get("code")).isEqualTo("PRICE_CHANGED");
    caches.invalidate(AppCaches.Name.FLASH);
  }

  // ---------- channels ----------
  @Test
  void aSaleCanBeOnlineOnlyOrCounterOnly() {
    Object online = newProduct(10000, 50).get("id");
    Object counter = newProduct(10000, 50).get("id");
    Map<String, Object> s1 = createRunning(online, null, Map.of("channel", "ONLINE"));
    Map<String, Object> s2 = createRunning(counter, null, Map.of("channel", "POS"));
    String cust = token(freshEmail());
    assertThat(quote(cust, online, 1).get("subtotalPaise")).isEqualTo(8000);
    assertThat(posQuote(online, 1).get("totalPaise")).isEqualTo(10000);
    assertThat(quote(cust, counter, 1).get("subtotalPaise")).isEqualTo(10000);
    assertThat(posQuote(counter, 1).get("totalPaise")).isEqualTo(8000);
    assertThat(shopItems("/api/v1/flash-sales/active")).noneMatch(i -> counter.equals(i.get("productId")));
    assertThat(shopItems("/api/v1/flash-sales/active")).anyMatch(i -> online.equals(i.get("productId")));
    assertThat(((Map) get("/api/v1/products/" + counter, null).getBody()).get("flash")).isNull();
    cancelSale(s1.get("id"));
    cancelSale(s2.get("id"));
  }

  @Test
  void weighedItemsAreNeverDiscountedAtTheCounter() {
    List<Map> found = (List<Map>) get("/api/v1/pos/products?q=Sugar", cashier()).getBody().get("items");
    Object sugar = found.get(0).get("productId");
    Map<String, Object> sale = createRunning(sugar, null, Map.of("channel", "POS"));
    var q = post("/api/v1/pos/quote", cashier(), Map.of("items", List.of(Map.of("productId", sugar, "qty", 1000))));
    assertThat(q.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(q.getBody().get("totalPaise")).as("1 kg at Rs 45").isEqualTo(4500);
    cancelSale(sale.get("id"));
  }

  @Test
  void anOfflineBillIsRecordedEvenWhenTheSaleRanOutMeanwhile() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, 1, Map.of());
    // someone else buys the only unit
    order(token(freshEmail()), pid, 1);
    Map cart = Map.of("items", List.of(Map.of("productId", pid, "qty", 2)));
    // the till priced 2 units at the sale price a moment ago; the server prices what is left (nothing) at the list price
    var upload = post("/api/v1/pos/sync/sales", cashier(), Map.of("sales", List.of(Map.of("idempotencyKey", UUID.randomUUID().toString(), "cart", cart,
        "payments", List.of(Map.of("mode", "CASH", "amountPaise", 20000))))));
    assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(((Map) ((List<Map>) upload.getBody().get("results")).get(0)).get("status")).isEqualTo("CREATED");
    assertThat(liveConsumed(sale.get("id"))).isEqualTo(1);
    cancelSale(sale.get("id"));
  }

  // ---------- editing ----------
  @Test
  void runningSalesOnlyChangeNameAndEndAndItemsOnlyBeforeAnythingSold() {
    Object p1 = newProduct(10000, 50).get("id");
    Object p2 = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(p1, 10, Map.of());
    Object id = sale.get("id");
    Instant newEnd = Instant.now().plusSeconds(7200);
    var ext = call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("name", "Renamed", "endsAt", newEnd.toString(), "description", "d", "bannerText", "b"), null);
    assertThat(ext.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(ext.getBody().get("name")).isEqualTo("Renamed");
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("discountValue", 30), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("perCustomerLimit", 1), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("endsAt", Instant.now().minusSeconds(5).toString()), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("name", " "), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    // items: add, then change before anything has sold
    var added = post(ADMIN_API + "/" + id + "/items", admin(), item(p2, 5));
    assertThat(added.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(post(ADMIN_API + "/" + id + "/items", admin(), item(p2, 5)).getStatusCode()).as("twice").isEqualTo(HttpStatus.BAD_REQUEST);
    Object item2 = saleItem(id, p2).get("id");
    Map<String, Object> change = item(p2, 8);
    change.put("discountKind", "FIXED_PRICE");
    change.put("discountValue", 7000);
    var changed = call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + item2, admin(), change, null);
    assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(saleItem(id, p2).get("salePricePaise")).isEqualTo(7000);
    assertThat(saleItem(id, p2).get("maxQty")).isEqualTo(8);
    assertThat(delete(ADMIN_API + "/" + id + "/items/" + item2).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(delete(ADMIN_API + "/" + id + "/items/" + item2).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

    // once a unit is sold the item is frozen except for raising the cap
    order(token(freshEmail()), p1, 1);
    Object item1 = saleItem(id, p1).get("id");
    assertThat(delete(ADMIN_API + "/" + id + "/items/" + item1).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> lower = item(p1, 5);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + item1, admin(), lower, null).getStatusCode()).as("cap cannot go down").isEqualTo(HttpStatus.CONFLICT);
    Map<String, Object> cheaper = item(p1, 10);
    cheaper.put("discountKind", "PERCENT");
    cheaper.put("discountValue", 50);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + item1, admin(), cheaper, null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + item1, admin(), item(p1, 25), null).getStatusCode()).as("cap can go up").isEqualTo(HttpStatus.OK);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + item1, admin(), item(p1, null), null).getStatusCode()).as("to unlimited").isEqualTo(HttpStatus.OK);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + item1, admin(), item(p1, 30), null).getStatusCode()).as("unlimited to capped = lower").isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + UUID.randomUUID(), admin(), item(p1, 30), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(saleItem(id, p1).get("soldQty")).isEqualTo(1);
    cancelSale(id);
  }

  @Test
  void aScheduledSaleCanBeEditedFreelyAndCancelled() {
    Object pid = newProduct(10000, 50).get("id");
    Instant start = Instant.now().plusSeconds(7200);
    Map<String, Object> sale = createSale(saleBody(List.of(item(pid, 5)), Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(3600).toString())));
    Object id = sale.get("id");
    var upd = call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("discountKind", "FIXED_PRICE", "discountValue", 9000, "channel", "POS", "perCustomerLimit", 3,
        "startsAt", start.plusSeconds(600).toString()), null);
    assertThat(upd.getStatusCode()).as(String.valueOf(upd.getBody())).isEqualTo(HttpStatus.OK);
    assertThat(saleItem(id, pid).get("salePricePaise")).isEqualTo(9000);
    assertThat(upd.getBody().get("channel")).isEqualTo("POS");
    // a fixed price at or above a product's price is refused
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("discountValue", 10000), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("discountKind", "BOGUS"), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(post(ADMIN_API + "/" + id + "/end-now", admin(), null).getStatusCode()).as("not running").isEqualTo(HttpStatus.CONFLICT);
    var cancelled = post(ADMIN_API + "/" + id + "/cancel", admin(), null);
    assertThat(cancelled.getBody().get("status")).isEqualTo("CANCELLED");
    assertThat(call(HttpMethod.PATCH, ADMIN_API + "/" + id, admin(), Map.of("name", "x"), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(delete(ADMIN_API + "/" + id + "/items/" + saleItem(id, pid).get("id")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(call(HttpMethod.PUT, ADMIN_API + "/" + id + "/items/" + saleItem(id, pid).get("id"), admin(), item(pid, 7), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(post(ADMIN_API + "/" + id + "/start-now", admin(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(post(ADMIN_API + "/" + id + "/cancel", admin(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void endNowStopsARunningSaleAndStartNowRefusesAPassedWindow() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    var ended = post(ADMIN_API + "/" + sale.get("id") + "/end-now", admin(), null);
    assertThat(ended.getBody().get("status")).isEqualTo("ENDED");
    caches.invalidate(AppCaches.Name.FLASH);
    assertThat(quote(token(freshEmail()), pid, 1).get("subtotalPaise")).isEqualTo(10000);

    Object p2 = newProduct(10000, 50).get("id");
    Instant start = Instant.now().plusSeconds(3600);
    Map<String, Object> later = createSale(saleBody(List.of(item(p2, null)), Map.of("startsAt", start.toString(), "endsAt", start.plusSeconds(60).toString())));
    jdbc.update("update flash_sale set starts_at = now() + interval '1 minute', ends_at = now() + interval '2 minutes' where id = ?::uuid", later.get("id").toString());
    jdbc.update("update flash_sale set ends_at = now() - interval '1 second', starts_at = now() - interval '1 hour' where id = ?::uuid and false", later.get("id").toString());
    assertThat(post(ADMIN_API + "/" + later.get("id") + "/start-now", admin(), null).getStatusCode()).isEqualTo(HttpStatus.OK);
    cancelSale(later.get("id"));
  }

  @Test
  void duplicateCopiesTheSaleOntoANewSchedule() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> own = item(pid, 7);
    own.put("perCustomerLimit", 2);
    Map<String, Object> sale = createSale(saleBody(List.of(own), Map.of("name", "Weekend", "bannerText", "Big", "channel", "ONLINE")));
    order(token(freshEmail()), pid, 1);
    Instant start = Instant.now().plusSeconds(86400);
    var copy = post(ADMIN_API + "/" + sale.get("id") + "/duplicate", admin(), Map.of("name", "Next weekend", "startsAt", start.toString(),
        "endsAt", start.plusSeconds(7200).toString()));
    assertThat(copy.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(copy.getBody().get("name")).isEqualTo("Next weekend");
    assertThat(copy.getBody().get("status")).isEqualTo("SCHEDULED");
    assertThat(copy.getBody().get("channel")).isEqualTo("ONLINE");
    Map copiedItem = saleItem(copy.getBody().get("id"), pid);
    assertThat(copiedItem.get("maxQty")).isEqualTo(7);
    assertThat(copiedItem.get("soldQty")).as("a fresh count").isEqualTo(0);
    assertThat(copiedItem.get("perCustomerLimit")).isEqualTo(2);
    // no body: same length starting now would overlap the running original
    assertThat(post(ADMIN_API + "/" + sale.get("id") + "/duplicate", admin(), null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    cancelSale(sale.get("id"));
    var plain = post(ADMIN_API + "/" + sale.get("id") + "/duplicate", admin(), null);
    assertThat(plain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(plain.getBody().get("name")).isEqualTo("Weekend (copy)");
    assertThat(post(ADMIN_API + "/" + UUID.randomUUID() + "/duplicate", admin(), null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    cancelSale(copy.getBody().get("id"));
    cancelSale(plain.getBody().get("id"));
  }

  @Test
  void theListFiltersByStatus() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> running = createRunning(pid, null, Map.of("name", "List running"));
    Object p2 = newProduct(10000, 50).get("id");
    Instant start = Instant.now().plusSeconds(86400);
    Map<String, Object> sched = createSale(saleBody(List.of(item(p2, null)), Map.of("name", "List scheduled", "startsAt", start.toString(),
        "endsAt", start.plusSeconds(60).toString())));
    Object p3 = newProduct(10000, 50).get("id");
    Map<String, Object> cancelled = createRunning(p3, null, Map.of("name", "List cancelled"));
    cancelSale(cancelled.get("id"));
    Object p4 = newProduct(10000, 50).get("id");
    Map<String, Object> ended = createRunning(p4, null, Map.of("name", "List ended"));
    endNowViaDb(ended.get("id"));
    for (var e : Map.of("ACTIVE", running, "SCHEDULED", sched, "CANCELLED", cancelled, "ENDED", ended).entrySet()) {
      Map page = get(ADMIN_API + "?status=" + e.getKey().toLowerCase() + "&size=50", admin()).getBody();
      assertThat((List<Map>) page.get("items")).as(e.getKey()).anySatisfy(s -> assertThat(s.get("id")).isEqualTo(e.getValue().get("id")));
      assertThat((List<Map>) page.get("items")).allSatisfy(s -> assertThat(s.get("status")).isEqualTo(e.getKey()));
    }
    Map all = get(ADMIN_API + "?size=50", admin()).getBody();
    assertThat(((Number) all.get("total")).longValue()).isGreaterThanOrEqualTo(4);
    assertThat((List<Map>) all.get("items")).allSatisfy(s -> assertThat(s.get("items")).isNotNull());
    cancelSale(running.get("id"));
    cancelSale(sched.get("id"));
  }

  // ---------- reports ----------
  @Test
  void reportsCountUnitsRevenueAndDiscountAndLeaveOutGivenBackOrders() {
    Object p1 = newProduct(10000, 50).get("id");
    Object p2 = newProduct(20000, 50).get("id");
    Map<String, Object> sale = createSale(saleBody(List.of(item(p1, 10), item(p2, null)), Map.of()));
    Object id = sale.get("id");
    String a = token(freshEmail());
    String b = token(freshEmail());
    order(a, p1, 2); // 2 x 8000
    String gone = order(b, p1, 1); // 1 x 8000, then cancelled
    post("/api/v1/orders/" + gone + "/cancel", b, null);
    order(b, p2, 1); // 1 x 16000
    var bill = posSale(p1, 3, 24000, null); // counter: 3 x 8000
    assertThat(bill.getStatusCode()).as(String.valueOf(bill.getBody())).isEqualTo(HttpStatus.CREATED);

    Map r = get(ADMIN_API + "/" + id + "/report", cashier()).getBody();
    assertThat(r.get("unitsSold")).isEqualTo(2 + 1 + 3);
    assertThat(r.get("orders")).isEqualTo(2);
    assertThat(r.get("bills")).isEqualTo(1);
    assertThat(r.get("revenuePaise")).isEqualTo(2 * 8000 + 16000 + 3 * 8000);
    assertThat(r.get("discountPaise")).isEqualTo(2 * 2000 + 4000 + 3 * 2000);
    assertThat(r.get("avgDiscountPercent")).isEqualTo(20.0);
    assertThat(r.get("customers")).as("the counter walk-in has no customer").isEqualTo(2);
    assertThat(r.get("sellThroughPercent")).as("5 of the 10 capped units (p2 has no cap)").isEqualTo(50.0);
    List<Map> byItem = (List<Map>) r.get("byItem");
    assertThat(byItem).hasSize(2);
    assertThat(byItem.get(0).get("productId")).isEqualTo(p1);
    assertThat(byItem.get(0).get("units")).isEqualTo(5);
    assertThat(byItem.get(0).get("sellThroughPercent")).isEqualTo(50.0);
    assertThat(byItem.get(1).get("sellThroughPercent")).isNull();
    List<Map> byChannel = (List<Map>) r.get("byChannel");
    assertThat(byChannel).extracting(m -> m.get("channel")).containsExactly("ONLINE", "POS");
    assertThat(byChannel.get(0).get("units")).isEqualTo(3);
    assertThat(byChannel.get(1).get("units")).isEqualTo(3);
    assertThat(byChannel.get(1).get("revenuePaise")).isEqualTo(24000);
    List<Map> byHour = (List<Map>) r.get("byHour");
    assertThat(byHour).hasSize(1);
    assertThat(byHour.get(0).get("hour").toString()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:00");
    assertThat(byHour.get(0).get("units")).isEqualTo(6);

    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
    Map range = get(ADMIN_API + "/report?from=" + today + "&to=" + today, admin()).getBody();
    List<Map> bySale = (List<Map>) range.get("bySale");
    assertThat(bySale).anySatisfy(s -> {
      assertThat(s.get("saleId")).isEqualTo(id);
      assertThat(s.get("unitsSold")).isEqualTo(6);
      assertThat(s.get("revenuePaise")).isEqualTo(56000);
    });
    assertThat(((Number) range.get("revenuePaise")).longValue()).isGreaterThanOrEqualTo(56000);
    assertThat((List<Map>) range.get("bestItems")).isNotEmpty();
    assertThat(get(ADMIN_API + "/report", admin()).getStatusCode()).as("defaults to the last 30 days").isEqualTo(HttpStatus.OK);
    cancelSale(id);
  }

  @Test
  void aSaleWithNoSalesReportsZeros() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    Map r = get(ADMIN_API + "/" + sale.get("id") + "/report", admin()).getBody();
    assertThat(r.get("unitsSold")).isEqualTo(0);
    assertThat(r.get("avgDiscountPercent")).isEqualTo(0.0);
    assertThat(r.get("sellThroughPercent")).isNull();
    assertThat(((List<Map>) r.get("byChannel"))).hasSize(2);
    assertThat((List<Map>) r.get("byHour")).isEmpty();
    cancelSale(sale.get("id"));
  }

  // ---------- lifecycle, notifications, observability ----------
  private boolean hasNote(String token, String title, String containing) {
    return ((List<Map>) get("/api/v1/me/notifications?size=50", token).getBody().get("items")).stream()
        .anyMatch(n -> title.equals(n.get("title")) && String.valueOf(n.get("body")).contains(containing));
  }

  @Test
  void theMinuteJobAnnouncesStartAndEndOnceAndAnItemSellingOutOnce() {
    String admin = admin();
    Object pid = newProduct(10000, 50).get("id");
    String name = "Lifecycle " + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> sale = createRunning(pid, 2, Map.of("name", name));
    lifecycle.tick();
    Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(hasNote(admin, "Flash sale started", name)).isTrue());
    assertThat(hasNote(cashier(), "Flash sale started", name)).as("cashiers are staff too").isTrue();
    assertThat(jdbc.queryForObject("select started_notified_at is not null from flash_sale where id = ?::uuid", Boolean.class, sale.get("id").toString())).isTrue();
    lifecycle.tick();
    lifecycle.tick();

    String cust = token(freshEmail());
    order(cust, pid, 2); // sells out the item
    Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(hasNote(admin, "Flash sale item sold out", name)).isTrue());
    String id2 = order(cust, pid, 1); // normal price now; no second alert
    post("/api/v1/orders/" + id2 + "/cancel", cust, null);

    jdbc.update("update flash_sale set starts_at = now() - interval '2 hours', ends_at = now() - interval '1 minute' where id = ?::uuid", sale.get("id").toString());
    caches.invalidate(AppCaches.Name.FLASH);
    lifecycle.tick();
    Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(hasNote(admin, "Flash sale ended", name)).isTrue());
    lifecycle.tick();
    Awaitility.await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      long started = ((List<Map>) get("/api/v1/me/notifications?size=50", admin).getBody().get("items")).stream()
          .filter(n -> String.valueOf(n.get("body")).contains(name)).filter(n -> "Flash sale started".equals(n.get("title"))).count();
      long ended = ((List<Map>) get("/api/v1/me/notifications?size=50", admin).getBody().get("items")).stream()
          .filter(n -> String.valueOf(n.get("body")).contains(name)).filter(n -> "Flash sale ended".equals(n.get("title"))).count();
      long soldOut = ((List<Map>) get("/api/v1/me/notifications?size=50", admin).getBody().get("items")).stream()
          .filter(n -> String.valueOf(n.get("body")).contains(name)).filter(n -> "Flash sale item sold out".equals(n.get("title"))).count();
      assertThat(started).isEqualTo(1);
      assertThat(ended).isEqualTo(1);
      assertThat(soldOut).isEqualTo(1);
    });
    Map ended = ((List<Map>) get("/api/v1/me/notifications?size=50", admin).getBody().get("items")).stream()
        .filter(n -> "Flash sale ended".equals(n.get("title")) && String.valueOf(n.get("body")).contains(name)).findFirst().orElseThrow();
    assertThat(ended.get("body").toString()).contains("Sold 2 units for Rs 160.00");
  }

  @Test
  void aSaleThatWasOverWhileTheServerWasDownIsClosedWithoutANotification() {
    Object pid = newProduct(10000, 50).get("id");
    String name = "Ancient " + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> sale = createRunning(pid, null, Map.of("name", name));
    jdbc.update("update flash_sale set starts_at = now() - interval '9 days', ends_at = now() - interval '8 days' where id = ?::uuid", sale.get("id").toString());
    caches.invalidate(AppCaches.Name.FLASH);
    lifecycle.tick();
    assertThat(jdbc.queryForObject("select ended_notified_at is not null from flash_sale where id = ?::uuid", Boolean.class, sale.get("id").toString())).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from outbox_event where payload like ?", Long.class, "%" + name + "%")).isZero();
  }

  @Test
  void staffActionsAreAuditedLoggedAndCounted() {
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    order(token(freshEmail()), pid, 2);
    call(HttpMethod.PATCH, ADMIN_API + "/" + sale.get("id"), admin(), Map.of("name", "Audited"), null);
    cancelSale(sale.get("id"));
    List<Map> audit = (List<Map>) get("/api/v1/admin/audit-log?entity=flash_sale&size=50", admin()).getBody().get("items");
    assertThat(audit).filteredOn(a -> sale.get("id").equals(a.get("entityId"))).extracting(a -> a.get("action"))
        .contains("FLASH_CREATE", "FLASH_UPDATE", "FLASH_CANCEL");
    assertThat(meters.find("mall.flash.units.sold").tag("channel", "ONLINE").counter().count()).isGreaterThanOrEqualTo(2);
    assertThat(meters.find("mall.flash.revenue.paise").tag("channel", "ONLINE").counter().count()).isGreaterThanOrEqualTo(16000);
    assertThat(meters.find("mall.flash.discount.paise").tag("channel", "ONLINE").counter().count()).isGreaterThanOrEqualTo(4000);
    assertThat(meters.find("mall.flash.price.lookup").timers()).isNotEmpty();
    assertThat(meters.find("mall.flash.sales").tag("state", "active").gauge()).isNotNull();
    assertThat(meters.find("mall.flash.sales").tag("state", "scheduled").gauge()).isNotNull();
  }

  @Test
  void theFlashCacheIsRegisteredAndEmptiedWhenASaleChanges() {
    assertThat(caches.stats()).anyMatch(s -> "flash".equals(s.name()) && s.ttlSeconds() == 15);
    long clears = caches.stats().stream().filter(s -> "flash".equals(s.name())).findFirst().orElseThrow().clears();
    Object pid = newProduct(10000, 50).get("id");
    Map<String, Object> sale = createRunning(pid, null, Map.of());
    cancelSale(sale.get("id"));
    Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(caches.stats().stream().filter(s -> "flash".equals(s.name())).findFirst().orElseThrow().clears()).isGreaterThan(clears));
    // the admin can also see and empty it with the other caches
    assertThat(get("/api/v1/admin/cache", admin()).getStatusCode().is2xxSuccessful()).isTrue();
  }

  // ---------- concurrency ----------
  @Test
  void buyersRacingForTheLastUnitsNeverOversellTheCap() throws Exception {
    Object pid = newProduct(10000, 500).get("id");
    Map<String, Object> sale = createRunning(pid, 7, Map.of());
    UUID saleId = UUID.fromString((String) sale.get("id"));
    UUID productId = UUID.fromString((String) pid);
    int threads = 24;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger ok = new AtomicInteger();
    AtomicInteger refused = new AtomicInteger();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      UUID user = UUID.randomUUID();
      futures.add(pool.submit((Callable<Void>) () -> {
        go.await();
        try {
          tx.executeWithoutResult(_ -> pricing.consume(FlashPricing.Channel.ONLINE, UUID.randomUUID(), user,
              List.of(new FlashPricing.Claim(saleId, productId, 1, 8000, 10000))));
          ok.incrementAndGet();
        } catch (FlashPricing.Refused e) {
          refused.incrementAndGet();
        }
        return null;
      }));
    }
    go.countDown();
    for (Future<?> f : futures) f.get();
    pool.shutdown();
    assertThat(ok.get()).isEqualTo(7);
    assertThat(refused.get()).isEqualTo(threads - 7);
    assertThat(soldQty(saleItem(sale.get("id"), pid).get("id"))).isEqualTo(7);
    assertThat(liveConsumed(sale.get("id"))).isEqualTo(7);
    cancelSale(sale.get("id"));
  }

  @Test
  void customersRacingThroughTheShopNeverGetMoreSaleUnitsThanTheCapAndOneCustomerNeverBeatsTheirLimit() throws Exception {
    Object pid = newProduct(10000, 500).get("id");
    Map<String, Object> sale = createRunning(pid, 5, Map.of("perCustomerLimit", 2));
    String lone = token(freshEmail());
    int threads = 12;
    List<String> tokens = new ArrayList<>();
    for (int i = 0; i < threads; i++) tokens.add(i < 4 ? lone : token(freshEmail())); // four requests from one customer
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (String t : tokens) {
      futures.add(pool.submit(() -> {
        long total = ((Number) quote(t, pid, 1).get("totalPaise")).longValue();
        go.await();
        return placeOrder(t, pid, 1, total).getStatusCode().value();
      }));
    }
    go.countDown();
    int created = 0;
    for (Future<Integer> f : futures) if (f.get() == 201) created++;
    pool.shutdown();
    long sold = soldQty(saleItem(sale.get("id"), pid).get("id"));
    assertThat(sold).isLessThanOrEqualTo(5);
    assertThat(sold).isEqualTo(liveConsumed(sale.get("id")));
    assertThat(created).as("every order that went through paid a price that matched its quote").isGreaterThanOrEqualTo((int) sold);
    assertThat(jdbc.queryForObject("select coalesce(max(s), 0) from (select sum(qty) s from flash_consumption where flash_sale_id = ?::uuid and released_at is null "
        + "group by user_id) x", Long.class, sale.get("id").toString())).isLessThanOrEqualTo(2);
    cancelSale(sale.get("id"));
  }
}
