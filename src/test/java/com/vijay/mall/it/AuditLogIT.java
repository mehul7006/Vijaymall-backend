package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/** The admin can read the audit trail, filter it, and nobody else can. */
@SuppressWarnings({"unchecked", "rawtypes"})
class AuditLogIT extends IntegrationTestBase {

  @Test
  void aRecordedActionCanBeFoundByActionEntityPersonAndDay() {
    String admin = adminToken();
    String coupon = "AUD" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    var made = post("/api/v1/admin/coupons", admin, Map.of("code", coupon, "kind", "FLAT", "value", 100));
    assertThat(made.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String couponId = (String) made.getBody().get("id");

    var byEntity = get("/api/v1/admin/audit-log?action=CREATE&entity=coupon&entityId=" + couponId, admin);
    assertThat(byEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
    List<Map> rows = (List<Map>) byEntity.getBody().get("items");
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("change")).isEqualTo(coupon);
    assertThat(rows.get(0).get("actorName")).as("shows who, not just an id").isNotNull();

    String actor = (String) rows.get(0).get("actorId");
    var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
    var mine = (List<Map>) get("/api/v1/admin/audit-log?actorId=" + actor + "&from=" + today + "&to=" + today + "&size=50", admin).getBody().get("items");
    assertThat(mine).anySatisfy(r -> assertThat(r.get("entityId")).isEqualTo(couponId));
    assertThat(((Number) get("/api/v1/admin/audit-log?entity=no-such-thing", admin).getBody().get("total")).longValue()).isZero();
  }

  @Test
  void badRangesAreRefusedAndOnlyTheAdminMayLook() {
    String admin = adminToken();
    assertThat(get("/api/v1/admin/audit-log?from=2026-11-08&to=2026-11-01", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/audit-log?from=2026-01-01&to=2026-11-01", admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(get("/api/v1/admin/audit-log", token("cashier@vijaysupermall.local")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(get("/api/v1/admin/audit-log", token(freshEmail())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(call(HttpMethod.GET, "/api/v1/admin/audit-log", null, null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
