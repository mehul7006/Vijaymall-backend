package com.vijay.mall.common.web;

import com.vijay.mall.common.AppProps;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * In dev the app serves /img itself; in production nginx serves the same folder. Also makes every {@code Instant} query parameter
 * accept the same forms as a JSON body field (see {@link RequestInstants}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AppProps props;

  /** Creates the configuration. */
  public WebConfig(AppProps props) { this.props = props; }

  /** Reads an {@code Instant} request parameter with {@link RequestInstants#parse}. */
  @Override
  public void addFormatters(FormatterRegistry registry) {
    registry.addConverter(String.class, Instant.class, RequestInstants::parse);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String dir = Path.of(props.imagesDir()).toAbsolutePath().toUri().toString();
    registry.addResourceHandler("/img/**").addResourceLocations(dir);
  }
}
