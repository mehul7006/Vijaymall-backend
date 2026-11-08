package com.vijay.mall.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class OtpSendersTest {
  @Test
  void smtpSenderMailsTheCodeWithTheConfiguredFromAddress() {
    JavaMailSender mail = mock(JavaMailSender.class);
    var fs = new FakeSettings().with(com.vijay.mall.config.Settings.OTP_FROM, "no-reply@shop.test");
    new SmtpEmailOtpSender(fs.settings) {
      @Override protected JavaMailSender createMailSender(String h, int p, String u, String pw) { return mail; }
    }.send("bob@x.com", "54321");
    var cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(cap.capture());
    SimpleMailMessage m = cap.getValue();
    assertThat(m.getFrom()).isEqualTo("no-reply@shop.test");
    assertThat(m.getTo()).containsExactly("bob@x.com");
    assertThat(m.getSubject()).contains("54321");
    assertThat(m.getText()).contains("54321").contains("5 minutes");
  }

  @Test
  void consoleSenderLogsTheCodeForDevelopment() {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ConsoleOtpSender.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      new ConsoleOtpSender().send("a@b.com", "12345");
    } finally {
      logger.detachAppender(appender);
    }
    assertThat(appender.list).singleElement()
        .satisfies(e -> assertThat(e.getFormattedMessage()).contains("a@b.com").contains("12345"));
  }
}
