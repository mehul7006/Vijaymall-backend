package com.vijay.mall.common.web;

import com.vijay.mall.common.AppProps;
import java.nio.file.Path;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** In dev the app serves /img itself; in production nginx serves the same folder. */
@Configuration
public class WebConfig implements WebMvcConfigurer {
  private final AppProps props;

  /** Creates the configuration. */
  public WebConfig(AppProps props) { this.props = props; }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    String dir = Path.of(props.imagesDir()).toAbsolutePath().toUri().toString();
    registry.addResourceHandler("/img/**").addResourceLocations(dir);
  }
}
