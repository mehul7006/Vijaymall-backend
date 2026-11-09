package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class WebFiltersTest {
  private final Logger access = (Logger) LoggerFactory.getLogger("access");
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void attach() { logs.start(); access.addAppender(logs); }

  @AfterEach
  void detach() { access.detachAppender(logs); MDC.clear(); }

  @Test
  void traceIdIsInHeaderAndMdcDuringTheRequestThenRemoved() throws Exception {
    var res = new MockHttpServletResponse();
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain chain = (rq, rs) -> seen.set(MDC.get(TraceIdFilter.KEY));
    new TraceIdFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), res, chain);
    assertThat(seen.get()).hasSize(8);
    assertThat(res.getHeader("X-Trace-Id")).isEqualTo(seen.get());
    assertThat(MDC.get(TraceIdFilter.KEY)).isNull();
  }

  @Test
  void traceIdIsRemovedEvenWhenTheChainThrows() {
    FilterChain boom = (rq, rs) -> { throw new IOException("x"); };
    try {
      new TraceIdFilter().doFilter(new MockHttpServletRequest("GET", "/a"), new MockHttpServletResponse(), boom);
    } catch (Exception ignored) {
      // expected
    }
    assertThat(MDC.get(TraceIdFilter.KEY)).isNull();
  }

  @Test
  void accessLogLineHasMethodPathStatusAndNoQueryString() throws Exception {
    var req = new MockHttpServletRequest("GET", "/api/v1/products");
    req.setQueryString("q=secret");
    req.setRemoteAddr("9.9.9.9");
    var res = new MockHttpServletResponse();
    new RequestLogFilter().doFilter(req, res, (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(200));
    assertThat(logs.list).singleElement().satisfies(e -> {
      assertThat(e.getLevel()).isEqualTo(Level.INFO);
      assertThat(e.getFormattedMessage()).startsWith("GET /api/v1/products -> 200 in ").endsWith("ip=9.9.9.9")
          .doesNotContain("secret");
    });
  }

  @Test
  void serverErrorsAreLoggedAsWarnings() throws Exception {
    new RequestLogFilter().doFilter(new MockHttpServletRequest("POST", "/api/v1/orders"), new MockHttpServletResponse(),
        (rq, rs) -> ((MockHttpServletResponse) rs).setStatus(500));
    assertThat(logs.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
  }

  @Test
  void slowRequestsAreLoggedAsWarnings() throws Exception {
    new RequestLogFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/slow"), new MockHttpServletResponse(),
        (rq, rs) -> {
            try {
                Thread.sleep(RequestLogFilter.SLOW_MS + 50);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    assertThat(logs.list).singleElement().satisfies(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
  }

  @Test
  void aThrowingChainStillProducesALogLine() {
    try {
      new RequestLogFilter().doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), new MockHttpServletResponse(),
          (rq, rs) -> { throw new IOException("x"); });
    } catch (Exception ignored) {
      // expected
    }
    assertThat(logs.list).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/actuator/health", "/img/abc_400.jpg"})
  void healthChecksAndImagesAreNotLogged(String path) throws Exception {
    new RequestLogFilter().doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(),
        (rq, rs) -> { });
    assertThat(logs.list).isEmpty();
  }
}
