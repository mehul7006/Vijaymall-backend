package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;

/** What the apps show about the shop, who may change it, and the switch that pauses online ordering. */
@SuppressWarnings({"unchecked", "rawtypes"})
class ShopProfileIT extends IntegrationTestBase {
  private static final String CASHIER = "cashier@vijaysupermall.local";

  private static List<Map<String, Object>> week(String open, String close) {
    List<Map<String, Object>> h = new ArrayList<>();
    for (String d : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"))
      h.add(Map.of("day", d, "open", open, "close", close, "closed", false));
    return h;
  }

  private Map<String, Object> body(boolean orders, String message) {
    return Map.of("email", "hello@vijay.in", "whatsapp", "+91 98765 43210", "mapUrl", "https://maps.example/vijay", "website", "https://vijay.in",
        "hours", week("08:00", "22:00"), "holidayNote", "Closed on Diwali", "onlineOrdersEnabled", orders, "onlineOrdersMessage", message);
  }

  @AfterEach
  void resume() { call(HttpMethod.PUT, "/api/v1/admin/shop-profile", adminToken(), body(true, ""), null); }

  @Test
  void onlyTheAdminEditsTheProfile() {
    assertThat(get("/api/v1/admin/shop-profile", token(CASHIER)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-profile", token(CASHIER), body(true, ""), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/shop-profile", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void whatTheAdminSavesIsWhatTheAppsGetAtOnce() {
    String admin = adminToken();
    var saved = call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, body(true, ""), null);
    assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
    var pub = get("/api/v1/shop-settings", null);
    assertThat(pub.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(pub.getBody()).containsEntry("email", "hello@vijay.in").containsEntry("whatsapp", "+919876543210").containsEntry("holidayNote", "Closed on Diwali")
        .containsEntry("onlineOrdersOpen", true).containsEntry("timezone", "Asia/Kolkata");
    assertThat(pub.getBody()).containsKeys("shopName", "phone", "address", "openNow", "todayHours", "hours", "logoUrl");
    assertThat(((List) pub.getBody().get("hours"))).hasSize(7);
    assertThat(pub.getBody().toString()).doesNotContain("gstin").doesNotContain("upi");

    // a second edit shows straight away (the cached copy is dropped on save)
    call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, Map.of("email", "new@vijay.in", "hours", week("09:00", "21:00")), null);
    assertThat(get("/api/v1/shop-settings", null).getBody()).containsEntry("email", "new@vijay.in");
  }

  @Test
  void theAppCanAskIfAnythingChangedAndGetsNothingBack() {
    var first = client().get().uri("/api/v1/shop-settings").retrieve().toEntity(String.class);
    String etag = first.getHeaders().getETag();
    assertThat(etag).isNotBlank();
    var again = client().get().uri("/api/v1/shop-settings").header("If-None-Match", etag).retrieve().toEntity(String.class);
    assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
  }

  @Test
  void badInputIsRefused() {
    String admin = adminToken();
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, Map.of("email", "nope", "hours", week("08:00", "22:00")), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, Map.of("hours", week("22:00", "08:00")), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, Map.of("mapUrl", "http://insecure.example"), null).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void logoCanBeUploadedShownAndRemoved() throws Exception {
    String admin = adminToken();
    var img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
    var out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    var form = new LinkedMultiValueMap<String, Object>();
    form.add("file", new ByteArrayResource(out.toByteArray()) { @Override public String getFilename() { return "logo.png"; } });
    var up = client().post().uri("/api/v1/admin/shop-profile/logo").headers(h -> h.setBearerAuth(admin)).contentType(MediaType.MULTIPART_FORM_DATA)
        .body(form).retrieve().toEntity(String.class);
    assertThat(up.getStatusCode()).as(up.getBody()).isEqualTo(HttpStatus.OK);
    String logo = (String) get("/api/v1/shop-settings", null).getBody().get("logoUrl");
    assertThat(logo).startsWith("/").contains("shop");

    assertThat(call(HttpMethod.DELETE, "/api/v1/admin/shop-profile/logo", admin, null, null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/shop-settings", null).getBody().get("logoUrl")).isIn("", null);

    var notAPicture = new LinkedMultiValueMap<String, Object>();
    notAPicture.add("file", new ByteArrayResource("hello".getBytes()) { @Override public String getFilename() { return "x.png"; } });
    var bad = client().post().uri("/api/v1/admin/shop-profile/logo").headers(h -> h.setBearerAuth(admin)).contentType(MediaType.MULTIPART_FORM_DATA)
        .body(notAPicture).retrieve().toEntity(String.class);
    assertThat(bad.getStatusCode().is4xxClientError()).isTrue();
  }

  @Test
  void pausingOnlineOrdersStopsNewOrdersWithTheShopsMessageAndResumingRestoresThem() {
    String admin = adminToken();
    String cust = token(freshEmail());
    restock("Potato Chips", 80);
    Object pid = productByName(cust, "Potato Chips").get("id");
    var items = List.of(Map.of("productId", pid, "qty", 1));
    Map<String, Object> quoteReq = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP");
    long total = ((Number) post("/api/v1/cart/quote", cust, quoteReq).getBody().get("totalPaise")).longValue();
    Map<String, Object> order = Map.of("items", items, "coinMode", "NONE", "fulfilment", "PICKUP", "paymentMethod", "COD", "expectedTotalPaise", total);

    call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, body(false, "Stock taking, back at 4 pm"), null);
    var paused = call(HttpMethod.POST, "/api/v1/orders", cust, order, UUID.randomUUID().toString());
    assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(paused.getBody()).containsEntry("code", "ORDERS_PAUSED");
    assertThat(paused.getBody().get("message").toString()).contains("back at 4 pm");
    var pub = get("/api/v1/shop-settings", null).getBody();
    assertThat(pub).containsEntry("onlineOrdersOpen", false);
    assertThat(pub.get("onlineOrdersMessage").toString()).contains("back at 4 pm");

    call(HttpMethod.PUT, "/api/v1/admin/shop-profile", admin, body(true, ""), null);
    var ok = call(HttpMethod.POST, "/api/v1/orders", cust, order, UUID.randomUUID().toString());
    assertThat(ok.getStatusCode()).as(String.valueOf(ok.getBody())).isEqualTo(HttpStatus.CREATED);
  }
}
