package com.vijay.mall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point of the Vijay Super Mall backend: a single Spring Boot application (modular monolith) serving the customer app, the admin and counter apps, and the payment webhooks. Scheduling is enabled for the background jobs (refund queue, outbox relay, order expiry, settings reload) and configuration properties are scanned from {@code AppProps}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MallApplication {
  /** Starts the application. */
  public static void main(String[] args) {
    SpringApplication.run(MallApplication.class, args);
  }
}
