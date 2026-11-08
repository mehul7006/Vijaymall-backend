package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

class CorsConfigCoverageTest {
  private final CorsConfig config = new CorsConfig();

  private static MockHttpServletRequest request(String uri) {
    var r = new MockHttpServletRequest("GET", uri);
    r.setServletPath(uri);
    return r;
  }

  @Test
  void noOriginsMeansNoCorsRulesAtAll() {
    var src = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource(List.of());
    assertThat(src.getCorsConfigurations()).isEmpty();
  }

  @Test
  void blankEntriesAreIgnoredSoOnlyBlanksMeansNoRules() {
    var src = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource(List.of("", "   "));
    assertThat(src.getCorsConfigurations()).isEmpty();
  }

  @Test
  void originsAreTrimmedAndApplyToApiAndImages() {
    var src = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource(List.of(" https://shop.example ", "", "http://localhost:*"));
    var api = src.getCorsConfigurations().get("/api/**");
    assertThat(api.getAllowedOriginPatterns()).containsExactly("https://shop.example", "http://localhost:*");
    assertThat(api.getAllowedMethods()).containsExactlyElementsOf(CorsConfig.METHODS);
    assertThat(api.getAllowedHeaders()).containsExactlyElementsOf(CorsConfig.HEADERS);
    assertThat(api.getExposedHeaders()).containsExactly("X-Trace-Id");
    assertThat(api.getAllowCredentials()).isFalse();
    assertThat(api.getMaxAge()).isEqualTo(3600L);

    var img = src.getCorsConfigurations().get("/img/**");
    assertThat(img.getAllowedMethods()).containsExactly("GET", "HEAD", "OPTIONS");
    assertThat(img.getAllowedHeaders()).containsExactly("*");
    assertThat(img.getAllowedOriginPatterns()).containsExactly("https://shop.example", "http://localhost:*");

    assertThat(src.getCorsConfiguration(request("/api/v1/products"))).isSameAs(api);
    assertThat(src.getCorsConfiguration(request("/other"))).isNull();
  }
}
