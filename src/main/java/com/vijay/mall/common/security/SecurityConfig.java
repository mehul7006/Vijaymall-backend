package com.vijay.mall.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ErrorBody;
import com.vijay.mall.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

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

  private static void write(HttpServletResponse res, JsonMapper om, HttpStatus s, String code, String msg)
      throws java.io.IOException {
    res.setStatus(s.value());
    res.setContentType("application/json");
    om.writeValue(res.getOutputStream(), new ErrorBody(code, msg, MDC.get(TraceIdFilter.KEY), Map.of()));
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http, JsonMapper om) throws Exception {
    AuthenticationEntryPoint entry =
        (req, res, ex) -> write(res, om, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Please sign in again");
    AccessDeniedHandler denied =
        (req, res, ex) -> write(res, om, HttpStatus.FORBIDDEN, "FORBIDDEN", "Not allowed");

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(jwt -> {
      Collection<GrantedAuthority> list =
          List.<GrantedAuthority>of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role")));
      return list;
    });

    http.csrf(csrf -> csrf.disable())
        // CorsFilter runs before authentication, so browser preflight (OPTIONS) is answered without a token.
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/api/v1/auth/**", "/api/v1/payments/webhook/**",
                "/actuator/health", "/actuator/health/**", "/img/**",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            // Guests may browse the catalog (SecurityIT.catalogIsPublic). Writing a review still needs a sign-in.
            .requestMatchers(HttpMethod.GET, "/api/v1/categories", "/api/v1/products", "/api/v1/products/*",
                "/api/v1/products/*/reviews", "/api/v1/hot-sale", "/api/v1/shop-settings").permitAll()
            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/pos/**").hasAnyRole("CASHIER", "ADMIN")
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o
            .jwt(j -> j.jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(entry)
            .accessDeniedHandler(denied))
        .exceptionHandling(e -> e.authenticationEntryPoint(entry).accessDeniedHandler(denied));
    return http.build();
  }
}
