package com.vijay.mall.identity.infra;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.app.SmsOtpSender;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Sends the SMS login code the way the admin set it up right now; the choice is made on every send. */
@Component
@Primary
public class RoutingSmsOtpSender implements SmsOtpSender {
  private final RuntimeSettings settings;
  private final ConsoleSmsOtpSender console;
  private final HttpSmsSender http;
  private final UnconfiguredSmsOtpSender unconfigured;

  /** Creates the router with the live settings and every SMS channel (wired by Spring). */
  public RoutingSmsOtpSender(RuntimeSettings settings, ConsoleSmsOtpSender console, HttpSmsSender http, UnconfiguredSmsOtpSender unconfigured) {
    this.settings = settings;
    this.console = console;
    this.http = http;
    this.unconfigured = unconfigured;
  }

  @Override
  public void send(String mobile, String code) {
    String channel = settings.get(Settings.SMS_CHANNEL).trim().toLowerCase();
    SmsOtpSender target = switch (channel) {
      case "console" -> console;
      case "http" -> http;
      default -> unconfigured;
    };
    target.send(mobile, code);
  }
}
