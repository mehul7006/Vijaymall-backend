package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** EventSource cannot send headers, so the stream paths (and only those) accept ?access_token=. */
class StreamTokenTest {
  private static MockHttpServletRequest req(String method, String uri, String token) {
    var r = new MockHttpServletRequest(method, uri);
    if (token != null) r.setParameter("access_token", token);
    return r;
  }

  @Test
  void streamPathsAcceptTheQueryToken() {
    assertThat(SecurityConfig.streamToken(req("GET", "/api/v1/orders/stream", " abc "))).isEqualTo("abc");
    assertThat(SecurityConfig.streamToken(req("GET", "/api/v1/admin/orders/stream", "xyz"))).isEqualTo("xyz");
  }

  @Test
  void otherPathsAndMethodsNeverAcceptIt() {
    assertThat(SecurityConfig.streamToken(req("GET", "/api/v1/orders", "abc"))).isNull();
    assertThat(SecurityConfig.streamToken(req("POST", "/api/v1/orders/stream", "abc"))).isNull();
    assertThat(SecurityConfig.streamToken(req("GET", "/api/v1/orders/stream", " "))).isNull();
    assertThat(SecurityConfig.streamToken(req("GET", "/api/v1/orders/stream", null))).isNull();
  }

  @Test
  void theHeaderWinsOverTheQueryToken() {
    var r = req("GET", "/api/v1/orders/stream", "query");
    r.addHeader("Authorization", "Bearer header");
    assertThat(SecurityConfig.bearerTokenResolver().resolve(r)).isEqualTo("header");
    assertThat(SecurityConfig.bearerTokenResolver().resolve(req("GET", "/api/v1/orders/stream", "query"))).isEqualTo("query");
    assertThat(SecurityConfig.bearerTokenResolver().resolve(req("GET", "/api/v1/me", "query"))).isNull();
  }
}
