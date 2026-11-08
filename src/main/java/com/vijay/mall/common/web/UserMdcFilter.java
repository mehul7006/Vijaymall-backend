package com.vijay.mall.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Once the caller is signed in, puts their user id and role in the log context, so every log line written while handling the
 * request (including the ones in services) carries who it was for. Runs after Spring Security. Also leaves both on the request,
 * for the access log, which is written after the security context has been cleared.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class UserMdcFilter extends OncePerRequestFilter {
  public static final String USER = "userId";
  public static final String ROLE = "role";

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    boolean set = false;
    if (auth instanceof JwtAuthenticationToken t && t.getToken().getSubject() != null) {
      String role = t.getToken().getClaimAsString("role");
      MDC.put(USER, t.getToken().getSubject());
      if (role != null) MDC.put(ROLE, role);
      req.setAttribute(USER, t.getToken().getSubject());
      req.setAttribute(ROLE, role);
      set = true;
    }
    try {
      chain.doFilter(req, res);
    } finally {
      if (set) {
        MDC.remove(USER);
        MDC.remove(ROLE);
      }
    }
  }
}
