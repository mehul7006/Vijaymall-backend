package com.vijay.mall.common;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start with development defaults, so a forgotten setting cannot go live. It runs in every profile except
 * dev and test, so starting the server with no profile at all (the known default JWT secret!) fails instead of running open.
 */
@Component
@Profile("!dev & !test")
public class ProdConfigGuard {
  public ProdConfigGuard(AppProps props) {
    List<String> problems = problems(props);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", problems));
    }
  }

  /** Pure check, unit tested. */
  public static List<String> problems(AppProps p) {
    List<String> out = new ArrayList<>();
    String secret = p.jwt() == null ? null : p.jwt().secret();
    if (secret == null || secret.length() < 32 || secret.startsWith("change-me")) out.add("JWT_SECRET must be a long random value");
    String pepper = p.otp() == null ? null : p.otp().pepper();
    if (pepper == null || pepper.length() < 16 || pepper.startsWith("dev-")) out.add("OTP_PEPPER must be a long random value");
    if (p.otp() != null && p.otp().fixedCode() != null && !p.otp().fixedCode().isBlank()) out.add("OTP_FIXED_CODE must be empty");
    if (p.otp() == null || !"email".equals(p.otp().channel())) out.add("OTP_CHANNEL must be email");
    if (p.bootstrapAdminEmail() == null || p.bootstrapAdminEmail().isBlank()) out.add("ADMIN_EMAIL is required for the first admin");
    AppProps.Payment pay = p.payment();
    if (pay != null && pay.razorpayKeyId() != null && !pay.razorpayKeyId().isBlank()
        && (pay.razorpayWebhookSecret() == null || pay.razorpayWebhookSecret().isBlank())) {
      out.add("RAZORPAY_WEBHOOK_SECRET is required when Razorpay keys are set (payments could never be confirmed)");
    }
    return out;
  }
}
