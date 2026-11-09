package com.vijay.mall.identity.infra;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.app.OtpSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Sends the e-mail login code the way the admin set it up right now: e-mail, or (default) only into the server log. */
@Component
@Primary
public class RoutingOtpSender implements OtpSender {
  private final RuntimeSettings settings;
  private final ConsoleOtpSender console;
  private final SmtpEmailOtpSender smtp;

  /** Creates the router with the live settings and both delivery channels (wired by Spring). */
  public RoutingOtpSender(RuntimeSettings settings, ConsoleOtpSender console, SmtpEmailOtpSender smtp) {
    this.settings = settings;
    this.console = console;
    this.smtp = smtp;
  }

  @Override
  public void send(String email, String code) {
    ("email".equalsIgnoreCase(settings.get(Settings.OTP_CHANNEL).trim()) ? smtp : console).send(email, code);
  }
}
