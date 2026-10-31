package com.vijay.mall.common.web;

import com.vijay.mall.common.ratelimit.RateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** One access-log line per API call: method, path, status, time, client. No query string, headers or bodies (privacy). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLogFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger("access");
  static final long SLOW_MS = 1000;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return p.startsWith("/actuator") || p.startsWith("/img/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    try {
      chain.doFilter(req, res);
    } finally {
      long ms = (System.nanoTime() - start) / 1_000_000;
      int status = res.getStatus();
      String line = "{} {} -> {} in {}ms ip={}";
      Object[] args = {req.getMethod(), req.getRequestURI(), status, ms, RateLimitFilter.clientIp(req)};
      if (status >= 500 || ms >= SLOW_MS) log.warn(line, args);
      else log.info(line, args);
    }
  }
}
