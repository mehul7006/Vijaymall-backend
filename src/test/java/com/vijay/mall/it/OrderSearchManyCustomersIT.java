package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The admin order search finds the orders of EVERY customer matching the text, not just the newest 200 of them. */
class OrderSearchManyCustomersIT extends IntegrationTestBase {
  @Test
  @SuppressWarnings("unchecked")
  void anOldMatchingCustomerIsFoundEvenWhenMoreThanTwoHundredNewerCustomersMatchToo() {
    String tag = "zqs" + UUID.randomUUID().toString().substring(0, 6);
    UUID oldest = null;
    for (int i = 0; i < 260; i++) {
      UUID id = UUID.randomUUID();
      if (i == 0) oldest = id;
      jdbc.update("insert into users (id, email, name, role, status, created_at, updated_at) values (?, ?, ?, 'CUSTOMER', 'ACTIVE', now() - make_interval(days => ?), now())",
          id, tag + i + "@example.com", tag + " customer " + i, 300 - i);
    }
    jdbc.update("insert into orders (id, order_no, user_id, idem_key, fulfilment, status, subtotal_paise, total_paise, payment_method, payment_status, placed_at, updated_at) "
        + "values (gen_random_uuid(), ?, ?, ?, 'PICKUP', 'PLACED', 100, 100, 'COD', 'PENDING', now(), now())", "ZQ-" + tag, oldest, UUID.randomUUID().toString());

    var page = get("/api/v1/admin/orders?size=50&q=" + tag, adminToken()).getBody();

    List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("orderNo")).isEqualTo("ZQ-" + tag);
    assertThat(page.get("total")).isEqualTo(1);
  }
}
