package com.vijay.mall.support;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Puts a signed-in user into the security context, the same way the real JWT filter does. */
public final class TestAuth {
  private TestAuth() {}

  public static void login(UUID userId, String role) {
    Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject(userId.toString()).claim("role", role).build();
    SecurityContextHolder.getContext()
        .setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
  }

  public static void clear() { SecurityContextHolder.clearContext(); }
}
