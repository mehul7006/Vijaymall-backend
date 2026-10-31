package com.vijay.mall.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All custom settings, bound from the "app" prefix in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProps(Jwt jwt, Otp otp, String bootstrapAdminEmail, String imagesDir, Payment payment, Delivery delivery) {

  /** Delivery fee in paise; free at or above freeAbovePaise. */
  public record Delivery(long feePaise, long freeAbovePaise) {}

  public record Jwt(String secret, long accessTtlSeconds, long refreshTtlDays) {}

  public record Otp(String channel, String pepper, String fixedCode, String from) {}

  public record Payment(String onlineProvider, long onlineTimeoutMinutes,
                        String razorpayKeyId, String razorpayKeySecret, String razorpayWebhookSecret,
                        String razorpayBaseUrl) {}
}
