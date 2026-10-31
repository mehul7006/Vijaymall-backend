package com.vijay.mall.common.ratelimit;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.error.ErrorBody;
import com.vijay.mall.common.web.TraceIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** 300 API calls per minute per client address (Cloudflare adds its own limits in front). */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final RateLimiter limiter;
  private final JsonMapper mapper;
  private final int perMinute;

  public RateLimitFilter(RateLimiter limiter, JsonMapper mapper,
                         @org.springframework.beans.factory.annotation.Value("${app.rate-limit.api-per-minute:300}") int perMinute) {
    this.perMinute = perMinute;
    this.limiter = limiter;
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }

  public static String clientIp(HttpServletRequest req) {
    String cf = req.getHeader("CF-Connecting-IP");
    if (cf != null && !cf.isBlank()) return cf.trim();
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
    return req.getRemoteAddr();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    try {
      limiter.check("ip:" + clientIp(req), perMinute, 60);
    } catch (ApiException e) {
      res.setStatus(e.status().value());
      res.setContentType("application/json");
      mapper.writeValue(res.getOutputStream(),
          new ErrorBody(e.code(), e.getMessage(), MDC.get(TraceIdFilter.KEY), e.details()));
      return;
    }
    chain.doFilter(req, res);
  }
}
