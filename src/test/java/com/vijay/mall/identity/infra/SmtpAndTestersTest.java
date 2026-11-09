package com.vijay.mall.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.ConnectionTester.Result;
import com.vijay.mall.config.Settings;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class SmtpAndTestersTest {
  private final FakeSettings fs = new FakeSettings();
  private final JavaMailSender mail = mock(JavaMailSender.class);
  private final List<String> created = new ArrayList<>();
  private final SmtpEmailOtpSender smtp = new SmtpEmailOtpSender(fs.settings) {
    @Override protected JavaMailSender createMailSender(String h, int p, String u, String pw) {
      created.add(h + ":" + p + ":" + u + ":" + pw);
      return mail;
    }
  };

  @Test
  void mailHasFromToSubjectAndTheCodeInTheBody() {
    fs.with(Settings.OTP_FROM, "shop@x.test");
    smtp.send("bob@x.com", "314159");
    var cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(cap.capture());
    assertThat(cap.getValue().getFrom()).isEqualTo("shop@x.test");
    assertThat(cap.getValue().getTo()).containsExactly("bob@x.com");
    assertThat(cap.getValue().getSubject()).isEqualTo("Your Vijay Super Mall code: 314159");
    assertThat(cap.getValue().getText()).contains("314159");
  }

  @Test
  void senderIsCachedUntilTheSmtpSettingsChange() {
    fs.with(Settings.SMTP_HOST, "smtp.a").with(Settings.SMTP_PORT, "25");
    smtp.send("a@b.com", "1");
    smtp.send("a@b.com", "2");
    assertThat(created).containsExactly("smtp.a:25::");
    fs.with(Settings.SMTP_USER, "u").with(Settings.SMTP_PASSWORD, "pw");
    smtp.send("a@b.com", "3");
    smtp.send("a@b.com", "4");
    assertThat(created).containsExactly("smtp.a:25::", "smtp.a:25:u:pw");
  }

  @Test
  void realSenderIsBuiltFromTheSettings() {
    var plain = new SmtpEmailOtpSender(fs.settings, mock(ShopIdentity.class));
    var impl = (JavaMailSenderImpl) plain.createMailSender("h", 2525, "", "");
    assertThat(impl.getHost()).isEqualTo("h");
    assertThat(impl.getPort()).isEqualTo(2525);
    assertThat(impl.getUsername()).isNull();
    assertThat(impl.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false").containsEntry("mail.smtp.starttls.enable", "true").containsEntry("mail.smtp.ssl.enable", "false")
        .containsEntry("mail.smtp.timeout", "10000").containsEntry("mail.smtp.connectiontimeout", "10000");
    var authed = (JavaMailSenderImpl) plain.createMailSender("h", 587, "me", "pw");
    assertThat(authed.getUsername()).isEqualTo("me");
    assertThat(authed.getPassword()).isEqualTo("pw");
    assertThat(authed.getJavaMailProperties()).containsEntry("mail.smtp.auth", "true").containsEntry("mail.smtp.starttls.enable", "true");
    var implicit = (JavaMailSenderImpl) plain.createMailSender("h", 465, "me", "pw");
    assertThat(implicit.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "true").containsEntry("mail.smtp.starttls.enable", "false");
  }

  // ---- e-mail tester
  private EmailConnectionTester emailTester() { return new EmailConnectionTester(fs.settings, smtp); }

  private void emailReady() {
    fs.with(Settings.OTP_CHANNEL, "email").with(Settings.SMTP_HOST, "smtp.a");
  }

  @Test
  void emailTesterRefusesWhenIncomplete() {
    var t = emailTester();
    assertThat(t.target()).isEqualTo("email");
    assertThat(t.test(Map.of("to", "a@b.com")).ok()).isFalse();      // channel console
    emailReady();
    fs.with(Settings.SMTP_HOST, " ");
    assertThat(t.test(Map.of("to", "a@b.com")).message()).contains("SMTP server");
    fs.with(Settings.SMTP_HOST, "smtp.a").with(Settings.OTP_FROM, " ");
    assertThat(t.test(Map.of("to", "a@b.com")).message()).contains("From");
    fs.with(Settings.OTP_FROM, "x@y.z").with(Settings.SMTP_USER, "u");
    assertThat(t.test(Map.of("to", "a@b.com")).message()).contains("password");
    fs.with(Settings.SMTP_PASSWORD, "p");
    assertThat(t.test(Map.of("to", "nope")).message()).contains("address");
    assertThat(t.test(null).ok()).isFalse();
    assertThat(t.test(new HashMap<>()).ok()).isFalse();
    verify(mail, never()).send(any(SimpleMailMessage.class));
  }

  @Test
  void emailTesterSendsAndReportsFailuresWithoutThrowing() {
    emailReady();
    var t = emailTester();
    Result ok = t.test(Map.of("to", " me@x.com "));
    assertThat(ok.ok()).isTrue();
    var cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail).send(cap.capture());
    assertThat(cap.getValue().getTo()).containsExactly("me@x.com");
    assertThat(cap.getValue().getSubject()).contains("test");
    doThrow(new MailSendException("boom", new ConnectException("refused"))).when(mail).send(any(SimpleMailMessage.class));
    Result bad = t.test(Map.of("to", "me@x.com"));
    assertThat(bad.ok()).isFalse();
    assertThat(bad.message()).contains("MailSendException").contains("ConnectException");
    doThrow(new IllegalStateException("x")).when(mail).send(any(SimpleMailMessage.class));
    assertThat(t.test(Map.of("to", "me@x.com")).message()).doesNotContain("(");
  }

  // ---- sms tester
  @Test
  void smsTesterRefusesWhenNotHttpOrIncomplete() {
    var http = mock(HttpSmsSender.class);
    var t = new SmsConnectionTester(fs.settings, http);
    assertThat(t.target()).isEqualTo("sms");
    assertThat(t.test(Map.of("mobile", "9876543210")).ok()).isFalse();
    fs.with(Settings.SMS_CHANNEL, "http");
    assertThat(t.test(Map.of("mobile", "9876543210")).message()).contains("address");
    fs.with(Settings.SMS_HTTP_URL, "https://x");
    assertThat(t.test(Map.of("mobile", "123")).message()).contains("10-digit");
    assertThat(t.test(null).ok()).isFalse();
    verify(http, never()).send(any(), any());
  }

  @Test
  void smsTesterSendsASixDigitCodeAndNeverThrows() {
    var http = mock(HttpSmsSender.class);
    var t = new SmsConnectionTester(fs.settings, http);
    fs.with(Settings.SMS_CHANNEL, "http").with(Settings.SMS_HTTP_URL, "https://x");
    Result ok = t.test(Map.of("mobile", " 9876543210 "));
    assertThat(ok.ok()).isTrue();
    assertThat(ok.message()).contains("98******10").doesNotContain("9876543210");
    var code = ArgumentCaptor.forClass(String.class);
    verify(http).send(ArgumentMatchers.eq("9876543210"), code.capture());
    assertThat(code.getValue()).matches("[0-9]{6}");

    doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "SMS_PROVIDER_ERROR", "x")).when(http).send(any(), any());
    Result bad = t.test(Map.of("mobile", "9876543210"));
    assertThat(bad.ok()).isFalse();
    assertThat(bad.message()).contains("SMS_PROVIDER_ERROR");
    doThrow(new IllegalStateException("x")).when(http).send(any(), any());
    assertThat(t.test(Map.of("mobile", "9876543210")).message()).contains("IllegalStateException");
    verify(http, times(3)).send(any(), any());
  }
}
