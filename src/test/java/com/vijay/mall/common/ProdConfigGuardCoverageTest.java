package com.vijay.mall.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The branches of the pure production-config check that the main test does not reach: missing sections. */
class ProdConfigGuardCoverageTest {
  private static final String SECRET = "k8s9d7f6g5h4j3k2l1m0n9b8v7c6x5z4q3w2e1r0";
  private static final String PEPPER = "p3pp3r-p3pp3r-p3pp3r";

  private static AppProps.Otp otp(String channel, String pepper, String fixed) {
    return new AppProps.Otp(channel, pepper, fixed, "x@y.z");
  }

  private static AppProps props(AppProps.Jwt jwt, AppProps.Otp otp, String admin, AppProps.Payment pay) {
    return new AppProps(jwt, otp, admin, "./img", pay, null);
  }

  private static AppProps.Jwt jwt(String secret) { return new AppProps.Jwt(secret, 900, 30); }

  @Test
  void missingJwtOtpAndAdminAreAllReported() {
    var p = ProdConfigGuard.problems(props(null, null, null, null));
    assertThat(p).hasSize(4)
        .anyMatch(s -> s.startsWith("JWT_SECRET"))
        .anyMatch(s -> s.startsWith("OTP_PEPPER"))
        .anyMatch(s -> s.startsWith("OTP_CHANNEL"))
        .anyMatch(s -> s.startsWith("ADMIN_EMAIL"));
  }

  @Test
  void nullSecretAndNullPepperAreRejected() {
    var p = ProdConfigGuard.problems(props(jwt(null), otp("email", null, null), "a@b.in", null));
    assertThat(p).hasSize(2).anyMatch(s -> s.startsWith("JWT_SECRET")).anyMatch(s -> s.startsWith("OTP_PEPPER"));
  }

  @Test
  void placeholderSecretAndShortPepperAreRejected() {
    var longPlaceholder = "change-me-" + "x".repeat(40);
    assertThat(ProdConfigGuard.problems(props(jwt(longPlaceholder), otp("email", PEPPER, ""), "a@b.in", null)))
        .singleElement().asString().startsWith("JWT_SECRET");
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), otp("email", "short", ""), "a@b.in", null)))
        .singleElement().asString().startsWith("OTP_PEPPER");
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), otp("email", "dev-" + "x".repeat(30), ""), "a@b.in", null)))
        .singleElement().asString().startsWith("OTP_PEPPER");
  }

  @Test
  void aFixedOtpCodeIsRejectedButBlankIsFine() {
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), otp("email", PEPPER, "12345"), "a@b.in", null)))
        .singleElement().asString().startsWith("OTP_FIXED_CODE");
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), otp("email", PEPPER, "   "), "a@b.in", null))).isEmpty();
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), otp("email", PEPPER, null), "a@b.in", null))).isEmpty();
  }

  @Test
  void blankAdminEmailIsRejected() {
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), otp("email", PEPPER, ""), "  ", null)))
        .singleElement().asString().startsWith("ADMIN_EMAIL");
  }

  @Test
  void razorpayWebhookSecretRules() {
    var good = otp("email", PEPPER, "");
    // no key id at all, or a blank one: nothing to check
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), good, "a@b.in",
        new AppProps.Payment("cod", 15, null, null, null, null)))).isEmpty();
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), good, "a@b.in",
        new AppProps.Payment("cod", 15, " ", null, null, null)))).isEmpty();
    // key id with a missing webhook secret
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), good, "a@b.in",
        new AppProps.Payment("razorpay", 15, "rzp", "s", null, null)))).singleElement().asString().startsWith("RAZORPAY_WEBHOOK_SECRET");
    // key id with a blank webhook secret
    assertThat(ProdConfigGuard.problems(props(jwt(SECRET), good, "a@b.in",
        new AppProps.Payment("razorpay", 15, "rzp", "s", " ", null)))).hasSize(1);
  }

  @Test
  void constructorAcceptsAGoodConfiguration() {
    new ProdConfigGuard(props(jwt(SECRET), otp("email", PEPPER, ""), "a@b.in", null));
    assertThatThrownBy(() -> new ProdConfigGuard(props(null, null, null, null)))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("Unsafe production configuration");
  }
}
