package com.vijay.mall.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

class CorsConfigTest {
  private static UrlBasedCorsConfigurationSource source(String origins) {
    return (UrlBasedCorsConfigurationSource) new CorsConfig(origins).corsConfigurationSource();
  }

  @Test
  void parseSplitsTrimsAndDropsBlanks() {
    assertThat(CorsConfig.parse(" http://a.com , http://b.com:*,, ")).containsExactly("http://a.com", "http://b.com:*");
    assertThat(CorsConfig.parse("")).isEmpty();
    assertThat(CorsConfig.parse("   ")).isEmpty();
    assertThat(CorsConfig.parse(null)).isEmpty();
  }

  @Test
  void noOriginsMeansNoCorsAtAll() {
    assertThat(source("").getCorsConfigurations()).isEmpty();
    assertThat(source(null).getCorsConfigurations()).isEmpty();
  }

  @Test
  void configuredOriginsGetTheApiHeadersAndMethodsTheAppNeeds() {
    var configs = source("https://shop.example.com,http://localhost:*").getCorsConfigurations();
    assertThat(configs).containsOnlyKeys("/api/**", "/img/**");
    var cfg = configs.get("/api/**");
    assertThat(cfg.getAllowedOriginPatterns()).containsExactly("https://shop.example.com", "http://localhost:*");
    assertThat(cfg.getAllowedHeaders()).contains("Authorization", "Content-Type", "Idempotency-Key");
    assertThat(cfg.getAllowedMethods()).containsAll(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    assertThat(cfg.getAllowCredentials()).isFalse();
    assertThat(cfg.getMaxAge()).isEqualTo(3600L);
  }
}
