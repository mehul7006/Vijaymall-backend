package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.support.IntegrationTestBase;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A full connection pool while checking a token answers the same 503 SERVICE_BUSY envelope, and never lets the request through. */
class PoolBusyIT extends IntegrationTestBase {
  @MockitoSpyBean UserRepository users;
  @Autowired AppCaches caches;

  @AfterEach
  void restore() { Mockito.reset(users); }

  @Test
  @SuppressWarnings("unchecked")
  void aFullPoolDuringTheAccountCheckIs503ForCustomersAndStaff() {
    String customer = token(freshEmail());
    String admin = adminToken();
    caches.invalidate(AppCaches.Name.BLOCKED_USERS);
    doThrow(new CannotGetJdbcConnectionException("pool", new SQLTransientConnectionException("timed out"))).when(users).isBlocked(any());

    for (String t : new String[] {customer, admin}) {
      var res = get("/api/v1/orders", t);
      assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(res.getHeaders().getFirst("Retry-After")).isEqualTo("2");
      assertThat(res.getBody().get("code")).isEqualTo("SERVICE_BUSY");
      assertThat((Map<String, Object>) res.getBody().get("details")).containsEntry("retryAfterSeconds", 2);
    }

    doReturn(false).when(users).isBlocked(any());
    assertThat(get("/api/v1/orders", customer).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
