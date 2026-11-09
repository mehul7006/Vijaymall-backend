package com.vijay.mall.common.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the one application {@link java.time.Clock} (UTC), so tests can replace it. */
@Configuration
public class ClockConfig {
  /** The system clock in UTC. */
  @Bean
  public Clock clock() { return Clock.systemUTC(); }
}
