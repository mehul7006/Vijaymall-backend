package com.vijay.mall.common;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with development defaults, so a forgotten setting cannot go live. It runs in every profile except
 * dev and test, so starting the server with no profile at all (the known default JWT secret!) fails instead of running open.
 */
@Component
@Profile("!dev & !test")
public class ProdConfigGuard {
  /**
   * Checks the configuration at start-up (a real server: no console login codes) and aborts with {@code IllegalStateException}
   * listing every problem found.
   */
  public ProdConfigGuard(AppProps props) {
    this(props, null);
  }

  /**
   * Checks the configuration at start-up and aborts with {@code IllegalStateException} listing every problem found. Login codes
   * written to the server log exist only in the {@code dev} profile, which this guard never runs in.
   *
   * @param props the application properties
   * @param env the environment, to read the mobile code channel; null means the default ({@code none})
   */
  @Autowired
  public ProdConfigGuard(AppProps props, Environment env) {
    List<String> problems = problems(props, env == null ? "none" : env.getProperty("app.otp.sms-channel", "none"));
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", problems));
    }
  }

  /** Pure check, unit tested. */
  public static List<String> problems(AppProps p) {
    return problems(p, "none");
  }

  /**
   * Pure check, unit tested.
   *
   * @param p the application properties
   * @param smsChannel the mobile code channel ({@code none}, {@code console} or {@code http})
   * @return every problem found, empty when the configuration is safe
   */
  public static List<String> problems(AppProps p, String smsChannel) {
    List<String> out = new ArrayList<>();
    String secret = p.jwt() == null ? null : p.jwt().secret();
    if (secret == null || secret.length() < 32 || secret.startsWith("change-me")) out.add("JWT_SECRET must be a long random value");
    String pepper = p.otp() == null ? null : p.otp().pepper();
    if (pepper == null || pepper.length() < 16 || pepper.startsWith("dev-")) out.add("OTP_PEPPER must be a long random value");
    if (p.otp() != null && p.otp().fixedCode() != null && !p.otp().fixedCode().isBlank()) out.add("OTP_FIXED_CODE must be empty");
    if (p.otp() == null || !"email".equals(p.otp().channel())) out.add("OTP_CHANNEL must be email (console codes exist only in the dev profile)");
    if ("console".equals(smsChannel)) out.add("SMS_CHANNEL must be http or none (console codes exist only in the dev profile)");
    if (p.bootstrapAdminEmail() == null || p.bootstrapAdminEmail().isBlank()) out.add("ADMIN_EMAIL is required for the first admin");
    AppProps.Payment pay = p.payment();
    if (pay != null && pay.razorpayKeyId() != null && !pay.razorpayKeyId().isBlank()
        && (pay.razorpayWebhookSecret() == null || pay.razorpayWebhookSecret().isBlank())) {
      out.add("RAZORPAY_WEBHOOK_SECRET is required when Razorpay keys are set (payments could never be confirmed)");
    }
    return out;
  }
}
