package com.vijay.mall.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Conditional GET for the public catalog lists (categories, product groups, units, hot sale): the answer carries an {@code ETag}
 * and {@code Cache-Control: no-cache}, and a repeat request with {@code If-None-Match} gets {@code 304} with no body when nothing
 * changed. Only these four URLs go through the filter, never streams, uploads or anything else.
 */
@Configuration
public class EtagConfig {
  /** The exact paths whose answers get an ETag. */
  static final String[] PATHS = {"/api/v1/categories", "/api/v1/product-groups", "/api/v1/units", "/api/v1/hot-sale"};

  /** The filter, limited to {@link #PATHS}. */
  @Bean
  FilterRegistrationBean<ShallowEtagHeaderFilter> catalogEtagFilter() {
    FilterRegistrationBean<ShallowEtagHeaderFilter> reg = new FilterRegistrationBean<>(new NoCacheEtagFilter());
    reg.addUrlPatterns(PATHS);
    reg.setName("catalogEtagFilter");
    return reg;
  }

  /** {@link ShallowEtagHeaderFilter} that also tells clients to revalidate every time. */
  static final class NoCacheEtagFilter extends ShallowEtagHeaderFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      response.setHeader("Cache-Control", "no-cache");
      super.doFilterInternal(request, response, chain);
    }
  }
}
