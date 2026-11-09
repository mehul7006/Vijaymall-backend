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

/** 300 API calls per minute per client address (Cloudflare adds its own limits in front). See {@link #clientIp}. */
@Component
public class RateLimitFilter extends OncePerRequestFilter {
  private final RateLimiter limiter;
  private final JsonMapper mapper;
  private final int perMinute;

  /**
   * Name of the one request header the reverse proxy sets to the real client address (for Cloudflare: CF-Connecting-IP).
   * Empty means no header is trusted and the socket address is used. A header is only safe to trust when the server
   * accepts traffic from that proxy alone; otherwise any client could invent it and dodge every limit.
   */
  private static volatile String trustedHeader = "";
  private com.vijay.mall.config.RuntimeSettings settings;

  public RateLimitFilter(RateLimiter limiter, JsonMapper mapper,
                         @org.springframework.beans.factory.annotation.Value("${app.rate-limit.api-per-minute:300}") int perMinute,
                         @org.springframework.beans.factory.annotation.Value("${app.rate-limit.client-ip-header:}") String ipHeader) {
    trustedHeader = ipHeader == null ? "" : ipHeader.trim();
    this.perMinute = perMinute;
    this.limiter = limiter;
    this.mapper = mapper;
  }

  /**
   * Optional (plain unit tests run without it): the admin's "real client address header" setting, which overrides the startup
   * value and applies at once when it is saved, no restart.
   */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setSettings(com.vijay.mall.config.RuntimeSettings settings) {
    this.settings = settings;
    applyHeader();
  }

  @org.springframework.context.event.EventListener
  void onSettingsChanged(com.vijay.mall.config.SettingsChanged e) {
    if (e.touches(com.vijay.mall.config.Settings.CLIENT_IP_HEADER)) applyHeader();
  }

  private void applyHeader() {
    if (settings == null) return;
    String v = settings.get(com.vijay.mall.config.Settings.CLIENT_IP_HEADER);
    trustedHeader = v == null ? "" : v.trim();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }

  public static String clientIp(HttpServletRequest req) {
    String name = trustedHeader;
    if (!name.isEmpty()) {
      String v = req.getHeader(name);
      if (v != null && !v.isBlank()) return v.split(",")[0].trim();
    }
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
