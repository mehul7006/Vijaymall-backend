package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The access log carries machine-readable fields, and the caller's id and role are in the log context while a request runs. */
class ObservabilityFiltersTest {
  private final Logger access = (Logger) LoggerFactory.getLogger("access");
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void attach() { logs.start(); access.addAppender(logs); }

  @AfterEach
  void detach() { access.detachAppender(logs); MDC.clear(); SecurityContextHolder.clearContext(); }

  private static JwtAuthenticationToken signedIn(String user, String role) {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(user).claim("role", role).issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60)).build();
    return new JwtAuthenticationToken(jwt);
  }

  private Map<String, Object> fields(ILoggingEvent e) {
    var out = new java.util.HashMap<String, Object>();
    e.getKeyValuePairs().forEach(kv -> out.put(kv.key, kv.value));
    return out;
  }

  @Test
  void theAccessLogHasSeparateFieldsForStatusDurationPathAndClient() throws Exception {
    var req = new MockHttpServletRequest("POST", "/api/v1/orders");
    req.setRemoteAddr("9.9.9.9");
    new RequestLogFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(201));
    var f = fields(logs.list.get(0));
    assertThat(f).containsEntry("http.method", "POST").containsEntry("http.path", "/api/v1/orders").containsEntry("http.status", 201)
        .containsEntry("clientIp", "9.9.9.9").containsKey("durationMs").doesNotContainKey("userId");
    assertThat(logs.list.get(0).getFormattedMessage()).startsWith("POST /api/v1/orders -> 201 in ");
  }

  @Test
  void whenTheCallerIsKnownTheirIdAndRoleAreAddedToTheLogLine() throws Exception {
    var req = new MockHttpServletRequest("GET", "/api/v1/me");
    req.setAttribute(UserMdcFilter.USER, "user-1");
    req.setAttribute(UserMdcFilter.ROLE, "CASHIER");
    new RequestLogFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { });
    assertThat(fields(logs.list.get(0))).containsEntry("userId", "user-1").containsEntry("role", "CASHIER");
  }

  @Test
  void aSignedInCallerIsInTheLogContextOnlyWhileTheRequestRuns() throws Exception {
    SecurityContextHolder.getContext().setAuthentication(signedIn("user-7", "ADMIN"));
    var req = new MockHttpServletRequest("GET", "/api/v1/x");
    AtomicReference<String> user = new AtomicReference<>();
    AtomicReference<String> role = new AtomicReference<>();
    new UserMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> { user.set(MDC.get("userId")); role.set(MDC.get("role")); });
    assertThat(user.get()).isEqualTo("user-7");
    assertThat(role.get()).isEqualTo("ADMIN");
    assertThat(req.getAttribute(UserMdcFilter.USER)).isEqualTo("user-7");
    assertThat(MDC.get("userId")).isNull();
    assertThat(MDC.get("role")).isNull();
  }

  @Test
  void aGuestLeavesTheContextAndTheRequestUntouched() throws Exception {
    var req = new MockHttpServletRequest("GET", "/api/v1/products");
    AtomicReference<String> user = new AtomicReference<>("unset");
    new UserMdcFilter().doFilter(req, new MockHttpServletResponse(), (rq, rs) -> user.set(MDC.get("userId")));
    assertThat(user.get()).isNull();
    assertThat(req.getAttribute(UserMdcFilter.USER)).isNull();
  }

  @Test
  void aTokenWithoutARoleStillIdentifiesTheUser() throws Exception {
    Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject("user-8").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    AtomicReference<String> user = new AtomicReference<>();
    AtomicReference<String> role = new AtomicReference<>("unset");
    new UserMdcFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (rq, rs) -> { user.set(MDC.get("userId")); role.set(MDC.get("role")); });
    assertThat(user.get()).isEqualTo("user-8");
    assertThat(role.get()).isNull();
  }
}
