package com.vijay.mall.common.ratelimit;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.error.ErrorBody;
import com.vijay.mall.common.web.TraceIdFilter;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.config.SettingsChanged;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Limits API calls per minute per client address (default 300, set with {@code app.rate-limit.api-per-minute}). See {@link #clientIp}. */
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
  private RuntimeSettings settings;

  /**
   * Creates the filter.
   * @param perMinute calls allowed per client address per minute
   * @param ipHeader name of the trusted proxy header carrying the real client address, or empty
   */
  public RateLimitFilter(RateLimiter limiter, JsonMapper mapper,
                         @Value("${app.rate-limit.api-per-minute:300}") int perMinute,
                         @Value("${app.rate-limit.client-ip-header:}") String ipHeader) {
    trustedHeader = ipHeader == null ? "" : ipHeader.trim();
    this.perMinute = perMinute;
    this.limiter = limiter;
    this.mapper = mapper;
  }

  /**
   * Optional (plain unit tests run without it): the admin's "real client address header" setting, which overrides the startup
   * value and applies at once when it is saved, no restart.
   */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) {
    this.settings = settings;
    applyHeader();
  }

  @EventListener
  void onSettingsChanged(SettingsChanged e) {
    if (e.touches(Settings.CLIENT_IP_HEADER)) applyHeader();
  }

  private void applyHeader() {
    if (settings == null) return;
    String v = settings.get(Settings.CLIENT_IP_HEADER);
    trustedHeader = v == null ? "" : v.trim();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    return !req.getRequestURI().startsWith("/api/");
  }

  /**
   * The client's address: the first value of the trusted proxy header when one is configured and present, otherwise the socket address.
   */
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
