package com.vijay.mall.common.web;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS for browser clients on another origin (the Flutter web app). Origins come from app.cors.allowed-origins
 * (env CORS_ALLOWED_ORIGINS, comma separated, patterns such as http://localhost:* allowed). Empty means no CORS
 * headers at all, which is the safe default.
 */
@Configuration
public class CorsConfig {
  static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  static final List<String> HEADERS = List.of("Authorization", "Content-Type", "Idempotency-Key", "Accept");

  private final List<String> origins;

  public CorsConfig(@Value("${app.cors.allowed-origins:}") String origins) { this.origins = parse(origins); }

  /** "a, b,,c" gives [a, b, c]; null or blank gives an empty list. */
  static List<String> parse(String csv) {
    if (csv == null) return List.of();
    return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    var source = new UrlBasedCorsConfigurationSource();
    if (origins.isEmpty()) return source;
    var cfg = new CorsConfiguration();
    cfg.setAllowedOriginPatterns(origins);
    cfg.setAllowedMethods(METHODS);
    cfg.setAllowedHeaders(HEADERS);
    cfg.setAllowCredentials(false); // bearer tokens, no cookies
    cfg.setMaxAge(3600L);
    source.registerCorsConfiguration("/api/**", cfg);
    source.registerCorsConfiguration("/img/**", cfg);
    return source;
  }
}
