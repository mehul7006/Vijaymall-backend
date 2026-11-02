package com.vijay.mall.common.security;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Lets the Flutter web build call the API from another origin. Origins come from {@code app.cors.allowed-origin-patterns}
 * (comma separated, e.g. {@code https://shop.example.com,http://localhost:*}). Empty means no cross-origin access.
 * The API uses bearer tokens, not cookies, so credentials are not allowed.
 */
@Configuration
public class CorsConfig {

  static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
  static final List<String> HEADERS = List.of("Authorization", "Content-Type", "Idempotency-Key", "Accept");

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${app.cors.allowed-origin-patterns:}") List<String> allowedOriginPatterns) {
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    List<String> origins = allowedOriginPatterns.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    if (origins.isEmpty()) return source;

    CorsConfiguration api = new CorsConfiguration();
    api.setAllowedOriginPatterns(origins);
    api.setAllowedMethods(METHODS);
    api.setAllowedHeaders(HEADERS);
    api.setExposedHeaders(List.of("X-Trace-Id"));
    api.setAllowCredentials(false);
    api.setMaxAge(3600L);
    source.registerCorsConfiguration("/api/**", api);

    // Flutter web (CanvasKit) fetches product images as bytes, so /img needs the same headers.
    CorsConfiguration images = new CorsConfiguration();
    images.setAllowedOriginPatterns(origins);
    images.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
    images.setAllowedHeaders(List.of("*"));
    images.setMaxAge(3600L);
    source.registerCorsConfiguration("/img/**", images);
    return source;
  }
}
