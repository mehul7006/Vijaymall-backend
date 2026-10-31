package com.vijay.mall.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Puts a short trace id in the log context and the X-Trace-Id response header. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
  public static final String KEY = "traceId";

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString().substring(0, 8);
    MDC.put(KEY, id);
    res.setHeader("X-Trace-Id", id);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove(KEY);
    }
  }
}
