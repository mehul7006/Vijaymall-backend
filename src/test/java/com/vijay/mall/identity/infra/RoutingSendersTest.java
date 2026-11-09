package com.vijay.mall.identity.infra;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.Settings;
import org.junit.jupiter.api.Test;

class RoutingSendersTest {
  private final FakeSettings fs = new FakeSettings();

  @Test
  void emailRouteFollowsTheSettingLive() {
    var console = mock(ConsoleOtpSender.class);
    var smtp = mock(SmtpEmailOtpSender.class);
    var r = new RoutingOtpSender(fs.settings, console, smtp);
    r.send("a@b.com", "1");
    verify(console).send("a@b.com", "1");
    fs.with(Settings.OTP_CHANNEL, " Email ");
    r.send("a@b.com", "2");
    verify(smtp).send("a@b.com", "2");
    fs.with(Settings.OTP_CHANNEL, "console");
    r.send("a@b.com", "3");
    verify(console).send("a@b.com", "3");
    verifyNoMoreInteractions(smtp);
  }

  @Test
  void smsRouteFollowsTheSettingLive() {
    var console = mock(ConsoleSmsOtpSender.class);
    var http = mock(HttpSmsSender.class);
    var r = new RoutingSmsOtpSender(fs.settings, console, http, new UnconfiguredSmsOtpSender());
    assertThatThrownBy(() -> r.send("9876543210", "1")).isInstanceOfSatisfying(ApiException.class,
        e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("SMS_NOT_CONFIGURED"));
    fs.with(Settings.SMS_CHANNEL, "console");
    r.send("9876543210", "2");
    verify(console).send("9876543210", "2");
    fs.with(Settings.SMS_CHANNEL, "HTTP");
    r.send("9876543210", "3");
    verify(http).send("9876543210", "3");
    fs.with(Settings.SMS_CHANNEL, "garbage");
    assertThatThrownBy(() -> r.send("9876543210", "4")).isInstanceOf(ApiException.class);
    verifyNoMoreInteractions(console, http);
  }

  @Test
  void consoleSmsSenderLogsTheCodeForDevelopment() {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ConsoleSmsOtpSender.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new ConsoleSmsOtpSender().send("9876543210", "13579");
    } finally {
      logger.detachAppender(appender);
    }
    org.assertj.core.api.Assertions.assertThat(appender.list).singleElement()
        .satisfies(e -> org.assertj.core.api.Assertions.assertThat(e.getFormattedMessage()).contains("9876543210").contains("13579"));
  }
}
