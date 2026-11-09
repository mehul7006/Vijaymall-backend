package com.vijay.mall.identity.infra;

import com.vijay.mall.config.ConnectionTester;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import java.util.Map;
import org.springframework.stereotype.Component;

/** "Test this setting" for e-mail: sends a test e-mail to the given address through the saved SMTP server. */
@Component
public class EmailConnectionTester implements ConnectionTester {
  private final RuntimeSettings settings;
  private final SmtpEmailOtpSender sender;

  /** Creates the tester with the live settings and the SMTP sender (wired by Spring). */
  public EmailConnectionTester(RuntimeSettings settings, SmtpEmailOtpSender sender) {
    this.settings = settings;
    this.sender = sender;
  }

  @Override
  public String target() { return "email"; }

  @Override
  public Result test(Map<String, String> args) {
    try {
      if (!"email".equalsIgnoreCase(settings.get(Settings.OTP_CHANNEL).trim())) {
        return new Result(false, "E-mail sending is not switched on. Choose \"email\", save, then test.");
      }
      if (!settings.isSet(Settings.SMTP_HOST) || !settings.isSet(Settings.OTP_FROM)) {
        return new Result(false, "The SMTP server and the From address must both be saved first.");
      }
      if (settings.isSet(Settings.SMTP_USER) && !settings.isSet(Settings.SMTP_PASSWORD)) {
        return new Result(false, "An SMTP user name is saved but the password is missing.");
      }
      String to = args == null ? "" : args.getOrDefault("to", "").trim();
      if (!to.matches("[^@\\s]+@[^@\\s]+")) return new Result(false, "Enter the e-mail address to send the test to.");
      sender.sendTest(to);
      return new Result(true, "A test e-mail was handed to the SMTP server for " + to + ". Check the inbox (and spam).");
    } catch (RuntimeException e) {
      return new Result(false, "The e-mail could not be sent: " + e.getClass().getSimpleName()
          + (e.getCause() == null ? "" : " (" + e.getCause().getClass().getSimpleName() + ")") + ". Check server, port, user and password.");
    }
  }
}
