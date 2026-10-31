package com.vijay.mall.common.security;

import com.vijay.mall.common.error.ApiException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the signed-in user from the JWT of the current request. */
public final class CurrentUser {
  private CurrentUser() {}

  private static Jwt jwt() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !(a.getPrincipal() instanceof Jwt jwt)) throw ApiException.unauthenticated();
    return jwt;
  }

  public static UUID id() { return UUID.fromString(jwt().getSubject()); }

  public static boolean isAdmin() { return "ADMIN".equals(jwt().getClaimAsString("role")); }
}
