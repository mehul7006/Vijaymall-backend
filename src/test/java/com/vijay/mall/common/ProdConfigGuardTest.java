package com.vijay.mall.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProdConfigGuardTest {
  private static AppProps props(String secret, String pepper, String fixed, String channel, String admin) {
    return new AppProps(new AppProps.Jwt(secret, 900, 30), new AppProps.Otp(channel, pepper, fixed, "x@y.z"), admin,
        "./img", null, new AppProps.Delivery(4000, 49900));
  }

  private static final String GOOD_SECRET = "k8s9d7f6g5h4j3k2l1m0n9b8v7c6x5z4q3w2e1r0";
  private static final String GOOD_PEPPER = "p3pp3r-p3pp3r-p3pp3r";

  @Test
  void acceptsAProperConfiguration() {
    assertTrue(ProdConfigGuard.problems(props(GOOD_SECRET, GOOD_PEPPER, "", "email", "owner@shop.in")).isEmpty());
  }

  @Test
  void flagsEveryDevelopmentDefault() {
    var p = ProdConfigGuard.problems(props("change-me-change-me-change-me-change-me-0123456789", "dev-pepper", "12345", "console", ""));
    assertEquals(5, p.size());
  }

  @Test
  void razorpayKeysNeedAWebhookSecret() {
    AppProps base = props(GOOD_SECRET, GOOD_PEPPER, "", "email", "owner@shop.in");
    AppProps withKeysOnly = new AppProps(base.jwt(), base.otp(), base.bootstrapAdminEmail(), base.imagesDir(),
        new AppProps.Payment("razorpay", 15, "rzp_key", "secret", "", "https://api.razorpay.com"), base.delivery());
    assertEquals(1, ProdConfigGuard.problems(withKeysOnly).size());
    AppProps complete = new AppProps(base.jwt(), base.otp(), base.bootstrapAdminEmail(), base.imagesDir(),
        new AppProps.Payment("razorpay", 15, "rzp_key", "secret", "whsec", "https://api.razorpay.com"), base.delivery());
    assertTrue(ProdConfigGuard.problems(complete).isEmpty());
  }

  @Test
  void shortSecretIsRejected() {
    assertEquals(1, ProdConfigGuard.problems(props("short", GOOD_PEPPER, "", "email", "o@s.in")).size());
  }

  @Test
  void constructorFailsWithAllProblemsListed() {
    var e = assertThrows(IllegalStateException.class,
        () -> new ProdConfigGuard(props("short", "dev-x", "1", "console", null)));
    assertTrue(e.getMessage().contains("JWT_SECRET") && e.getMessage().contains("OTP_CHANNEL"));
  }
}
