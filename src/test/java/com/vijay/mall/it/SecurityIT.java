package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Who may call what. */
class SecurityIT extends IntegrationTestBase {

  @Test
  void catalogIsPublic() {
    assertThat(get("/api/v1/products", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/v1/categories", null).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void couponOffersArePublicButTheyCannotBeChangedAnonymously() {
    assertThat(get("/api/v1/coupons", null).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(post("/api/v1/coupons", null, Map.of("code", "X")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(get("/api/v1/admin/coupons", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void ordersNeedSignIn() {
    var res = get("/api/v1/orders", null);
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(res.getBody().get("code")).isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void customerCannotUseAdminApi() {
    var res = get("/api/v1/admin/dashboard", token(freshEmail()));
    assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(res.getBody().get("code")).isEqualTo("FORBIDDEN");
  }

  @Test
  void adminCanUseAdminApi() {
    assertThat(get("/api/v1/admin/dashboard", adminToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void garbageTokenIsRejected() {
    assertThat(get("/api/v1/wallet", "not.a.token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void unknownRouteIs404NotServerError() {
    assertThat(get("/api/v1/nope", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void webhookWithBadSignatureIsRejected() {
    var res = post("/api/v1/payments/webhook/razorpay", null, Map.of("event", "payment.captured"));
    assertThat(res.getStatusCode().is4xxClientError()).isTrue();
  }
}
