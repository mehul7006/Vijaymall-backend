package com.vijay.mall.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All custom settings, bound from the "app" prefix in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProps(Jwt jwt, Otp otp, String bootstrapAdminEmail, String imagesDir, Payment payment, Delivery delivery) {

  /** Delivery fee in paise; free at or above freeAbovePaise. */
  public record Delivery(long feePaise, long freeAbovePaise) {}

  /**
   * Sign-in token settings.
   * @param secret the HMAC signing secret, at least 32 characters (refused at start-up otherwise)
   * @param accessTtlSeconds life of an access token, in seconds
   * @param refreshTtlDays life of a refresh token, in days
   */
  public record Jwt(String secret, long accessTtlSeconds, long refreshTtlDays) {}

  /**
   * Login-code settings.
   * @param channel {@code email} or {@code console} (console only writes the code to the log, for development)
   * @param pepper secret mixed into stored code hashes
   * @param fixedCode a constant code for tests; must be empty in production
   * @param from sender address of login e-mails
   */
  public record Otp(String channel, String pepper, String fixedCode, String from) {}

  /**
   * Online payment settings; the keys and timeout can be overridden by the admin at run time (see {@code RuntimeSettings}).
   * @param onlineProvider gateway name used for the {@code ONLINE} method, for example {@code razorpay}
   * @param onlineTimeoutMinutes minutes a customer has to pay before the order is cancelled
   * @param razorpayKeyId Razorpay key id; empty means online payment is not set up
   * @param razorpayKeySecret Razorpay key secret
   * @param razorpayWebhookSecret secret that signs Razorpay webhooks
   * @param razorpayBaseUrl API address, blank for the real Razorpay (a stub in tests)
   */
  public record Payment(String onlineProvider, long onlineTimeoutMinutes,
                        String razorpayKeyId, String razorpayKeySecret, String razorpayWebhookSecret,
                        String razorpayBaseUrl) {}
}
