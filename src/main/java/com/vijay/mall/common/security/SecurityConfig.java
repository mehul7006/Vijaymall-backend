package com.vijay.mall.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.error.DatabaseBusy;
import com.vijay.mall.common.error.ErrorBody;
import com.vijay.mall.common.web.TraceIdFilter;
import com.vijay.mall.identity.infra.UserRepository;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Security set-up: stateless bearer-token (JWT) authentication, CORS and the route rules.
 * <p>Public: sign-in, payment webhooks, health and metrics, API docs, product images and the guest catalog reads. {@code /admin/orders} and {@code /admin/flash-sales} are open to ADMIN and CASHIER, every other {@code /admin} route to ADMIN only, {@code /pos} to CASHIER and ADMIN, and everything else needs a signed-in user.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  SecretKey jwtSecretKey(AppProps props) {
    byte[] bytes = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) throw new IllegalStateException("app.jwt.secret must be at least 32 characters");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKey key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(SecretKey key) {
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  /** Live order streams: a browser EventSource cannot send an Authorization header, so these two GETs also accept ?access_token=. */
  static final List<String> STREAM_PATHS = List.of("/api/v1/orders/stream", "/api/v1/admin/orders/stream");

  /** The Authorization header when there is one; otherwise ?access_token= on the stream paths only, never anywhere else. */
  static String streamToken(HttpServletRequest req) {
    if (!"GET".equals(req.getMethod()) || !STREAM_PATHS.contains(req.getRequestURI())) return null;
    String t = req.getParameter("access_token");
    return t == null || t.isBlank() ? null : t.trim();
  }

  static BearerTokenResolver bearerTokenResolver() {
    DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
    return req -> {
      String fromHeader = header.resolve(req);
      return fromHeader != null ? fromHeader : streamToken(req);
    };
  }

  /** Staff tokens are checked against the account on every call, uncached. */
  static boolean isBlockedStaff(String role, String subject, UserRepository users) {
    if (!"CASHIER".equals(role) && !"ADMIN".equals(role)) return false;
    UUID id = parse(subject);
    return id != null && users.isBlocked(id);
  }

  private static UUID parse(String subject) {
    try {
      return UUID.fromString(subject);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * Customers' tokens are re-checked too, but through a 10-second cache, so the database sees each customer at most once per
   * 10 seconds. Staff are not handled here (see {@link #isBlockedStaff}).
   */
  static boolean isBlockedCustomer(String role, String subject, UserRepository users, AppCaches caches) {
    if ("CASHIER".equals(role) || "ADMIN".equals(role)) return false;
    UUID id = parse(subject);
    return id != null && caches.get(AppCaches.Name.BLOCKED_USERS, id, () -> users.isBlocked(id));
  }

  /**
   * Refuses a token whose account is blocked. Fails closed: if the account cannot be looked up, the request is NOT let through. A full
   * connection pool becomes {@link DatabaseBusyAuthenticationException} (answered 503 SERVICE_BUSY); any other lookup failure propagates
   * as an error, never as "not blocked".
   */
  static void requireNotBlocked(Jwt jwt, UserRepository users, AppCaches caches) {
    String role = jwt.getClaimAsString("role");
    boolean blocked;
    try {
      blocked = isBlockedStaff(role, jwt.getSubject(), users) || isBlockedCustomer(role, jwt.getSubject(), users, caches);
    } catch (RuntimeException e) {
      if (DatabaseBusy.causes(e)) throw new DatabaseBusyAuthenticationException(e);
      throw e;
    }
    // A blocked cashier or admin loses access at once, a blocked customer within 10 seconds, not when the 15-minute token runs out.
    if (blocked) throw new DisabledException("account blocked");
  }

  /** Writes the busy answer: 503 SERVICE_BUSY with Retry-After. */
  static void writeBusy(HttpServletResponse res, JsonMapper om) throws IOException {
    res.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    res.setHeader("Retry-After", String.valueOf(DatabaseBusy.RETRY_AFTER_SECONDS));
    res.setContentType("application/json");
    om.writeValue(res.getOutputStream(), DatabaseBusy.body(MDC.get(TraceIdFilter.KEY)));
  }

  private static void write(HttpServletResponse res, JsonMapper om, HttpStatus s, String code, String msg)
      throws IOException {
    res.setStatus(s.value());
    res.setContentType("application/json");
    om.writeValue(res.getOutputStream(), new ErrorBody(code, msg, MDC.get(TraceIdFilter.KEY), Map.of()));
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JsonMapper om,
                                  UserRepository users, AppCaches caches) throws Exception {
    AuthenticationEntryPoint entry = (req, res, ex) -> {
      if (ex instanceof DatabaseBusyAuthenticationException) writeBusy(res, om);
      else write(res, om, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in again");
    };
    AccessDeniedHandler denied =
        (req, res, ex) -> write(res, om, HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      requireNotBlocked(jwt, users, caches);
      Collection<GrantedAuthority> list =
          List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role")));
      return list;
    });

    http.csrf(csrf -> csrf.disable())
        // CorsFilter runs before authentication, so browser preflight (OPTIONS) is answered without a token.
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            // The stream is authorized when it opens; its later async dispatch (when it ends) must not be checked again.
            .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
            .requestMatchers("/api/v1/auth/**", "/api/v1/payments/webhook/**",
                "/actuator/health", "/actuator/health/**", "/img/**",
                // Served only on the private management port (ManagementPortGuard refuses to start otherwise), where Prometheus scrapes it.
                "/actuator/prometheus",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            // Guests may browse the catalog (SecurityIT.catalogIsPublic). Writing a review still needs a sign-in.
            .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/products", "/api/v1/products/*",
                "/api/v1/products/*/reviews", "/api/v1/hot-sale", "/api/v1/shop-settings", "/api/v1/coupons",
                "/api/v1/product-groups", "/api/v1/product-groups/*", "/api/v1/product-groups/*/reviews", "/api/v1/units").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/flash-sales/active", "/api/v1/flash-sales/upcoming").permitAll()
            // Counter staff (CASHIER) work the orders too; everything else under /admin stays admin-only.
            .requestMatchers("/api/v1/admin/orders", "/api/v1/admin/orders/**").hasAnyRole("ADMIN", "CASHIER")
            // Flash sales are set up by the shop staff too (admin and cashier).
            .requestMatchers("/api/v1/admin/flash-sales", "/api/v1/admin/flash-sales/**").hasAnyRole("ADMIN", "CASHIER")
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/pos/**").hasAnyRole("CASHIER", "ADMIN")
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o
            .bearerTokenResolver(bearerTokenResolver())
            .jwt(j -> j.jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(entry)
            .accessDeniedHandler(denied))
        .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied));
    return http.build();
  }
}
