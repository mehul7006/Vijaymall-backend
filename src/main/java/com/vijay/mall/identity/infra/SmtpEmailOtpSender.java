package com.vijay.mall.identity.infra;

import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.app.OtpSender;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Sends the code by e-mail through the SMTP server saved in the settings; a change in the admin screen applies to the next mail. */
@Component
public class SmtpEmailOtpSender implements OtpSender {
  private static final Logger log = LoggerFactory.getLogger(SmtpEmailOtpSender.class);
  private static final String FALLBACK_NAME = "Vijay Super Mall";

  /** The SMTP connection settings; when they change, a new mail sender is built. */
  private record Key(String host, int port, String user, String password) {}
  /** The mail sender built for one set of connection settings. */
  private record Cached(Key key, JavaMailSender sender) {}

  private final RuntimeSettings settings;
  private final ShopIdentity shop;
  private Cached cached;

  /** Without the shop settings the mail uses the default name (plain unit tests). */
  public SmtpEmailOtpSender(RuntimeSettings settings) { this(settings, null); }

  /**
   * Creates the sender with the live settings and the shop identity used for the name in the mail (wired by Spring).
   */
  @Autowired
  public SmtpEmailOtpSender(RuntimeSettings settings, ShopIdentity shop) {
    this.settings = settings;
    this.shop = shop;
  }

  /** The shop name the admin set in shop settings, read each time so an edit shows in the next mail. */
  private String shopName() {
    String name = shop == null ? null : shop.identity().name();
    return name == null || name.isBlank() ? FALLBACK_NAME : name;
  }

  /**
   * Sends the sign-in code. When the mail server cannot be reached or refuses the mail (a wrong SMTP setting, a full outbox) the
   * customer gets a clear 503 they can retry, not an "unhandled error"; the real reason is written to the server log.
   *
   * @throws ApiException 503 {@code OTP_EMAIL_FAILED} when the mail could not be sent
   */
  @Override
  public void send(String email, String code) {
    try {
      deliver(email, "Your " + shopName() + " code: " + code,
          "Your sign-in code is " + code + ". It is valid for 5 minutes. Do not share it with anyone.");
    } catch (MailException e) {
      log.error("Could not send the sign-in code e-mail ({}): {}", e.getClass().getSimpleName(), e.getMessage());
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OTP_EMAIL_FAILED", "We could not send the sign-in code right now. Please try again in a moment.");
    }
  }

  /** A harmless message for the admin's "test this setting" button. Failures propagate as mail exceptions. */
  public void sendTest(String to) {
    deliver(to, shopName() + " test e-mail", "This is a test e-mail from " + shopName() + ". Your e-mail settings work.");
  }

  private void deliver(String to, String subject, String text) {
    SimpleMailMessage m = new SimpleMailMessage();
    m.setFrom(settings.get(Settings.OTP_FROM));
    m.setTo(to);
    m.setSubject(subject);
    m.setText(text);
    mailSender().send(m);
  }

  /** The mail sender for the settings in force now; rebuilt only when host, port, user or password changed. */
  private synchronized JavaMailSender mailSender() {
    Key key = new Key(settings.get(Settings.SMTP_HOST).trim(), settings.getInt(Settings.SMTP_PORT), settings.get(Settings.SMTP_USER).trim(),
        settings.get(Settings.SMTP_PASSWORD));
    if (cached == null || !cached.key().equals(key)) cached = new Cached(key, createMailSender(key.host(), key.port(), key.user(), key.password()));
    return cached.sender();
  }

  /** Builds the real sender; tests override this to avoid any network. */
  protected JavaMailSender createMailSender(String host, int port, String user, String password) {
    JavaMailSenderImpl impl = new JavaMailSenderImpl();
    impl.setHost(host);
    impl.setPort(port);
    boolean auth = !user.isEmpty();
    if (auth) {
      impl.setUsername(user);
      impl.setPassword(password);
    }
    Properties p = impl.getJavaMailProperties();
    p.put("mail.smtp.auth", Boolean.toString(auth));
    // Port 465 is implicit TLS (the connection is encrypted from the first byte). Anywhere else, upgrade to TLS whenever the
    // server offers it (STARTTLS), with or without a login, so codes are not sent in clear text to a server that can do better.
    boolean implicitTls = port == 465;
    p.put("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
    p.put("mail.smtp.starttls.enable", Boolean.toString(!implicitTls));
    p.put("mail.smtp.connectiontimeout", "10000");
    p.put("mail.smtp.timeout", "10000");
    p.put("mail.smtp.writetimeout", "10000");
    return impl;
  }
}
