package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EtagConfigTest {
  @Test
  void onlyTheCatalogListsAndTheGstMasterReadsAreRegisteredAndNeverTheSuggest() {
    var reg = new EtagConfig().catalogEtagFilter();
    assertThat(reg.getUrlPatterns()).containsExactlyInAnyOrder("/api/v1/categories", "/api/v1/product-groups", "/api/v1/units", "/api/v1/hot-sale",
        "/api/v1/gst/hsn", "/api/v1/gst/hsn/*", "/api/v1/gst/rates");
    assertThat(reg.getUrlPatterns()).doesNotContain("/api/v1/gst/suggest");
  }

  @Test
  void answerGetsAnEtagAndNoCacheAndARepeatGets304() throws Exception {
    var filter = new EtagConfig.NoCacheEtagFilter();
    var first = new MockHttpServletResponse();
    filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/units"), first, write("[1]"));
    assertThat(first.getHeader("ETag")).isNotBlank();
    assertThat(first.getHeader("Cache-Control")).isEqualTo("no-cache");
    assertThat(first.getContentAsString()).isEqualTo("[1]");

    var req = new MockHttpServletRequest("GET", "/api/v1/units");
    req.addHeader("If-None-Match", first.getHeader("ETag"));
    var again = new MockHttpServletResponse();
    filter.doFilter(req, again, write("[1]"));
    assertThat(again.getStatus()).isEqualTo(304);
    assertThat(again.getContentAsString()).isEmpty();
  }

  private static MockFilterChain write(String body) {
    return new MockFilterChain(new HttpServlet() {
      @Override
      protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setContentType("application/json");
        res.getWriter().write(body);
      }
    });
  }
}
