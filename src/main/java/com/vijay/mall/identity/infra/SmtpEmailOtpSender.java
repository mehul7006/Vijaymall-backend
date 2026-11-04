package com.vijay.mall.identity.infra;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.ShopIdentity;
import com.vijay.mall.identity.app.OtpSender;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends the code by email through the SMTP server in spring.mail.*. */
@Component
@ConditionalOnProperty(name = "app.otp.channel", havingValue = "email")
public class SmtpEmailOtpSender implements OtpSender {
  private final JavaMailSender mail;
  private static final String FALLBACK_NAME = "Vijay Super Mall";

  private final AppProps props;
  private final ShopIdentity shop;

  /** Without the shop settings the mail uses the default name (plain unit tests). */
  public SmtpEmailOtpSender(JavaMailSender mail, AppProps props) { this(mail, props, null); }

  @Autowired
  public SmtpEmailOtpSender(JavaMailSender mail, AppProps props, ShopIdentity shop) {
    this.mail = mail;
    this.props = props;
    this.shop = shop;
  }

  /** The shop name the admin set in shop settings, read each time so an edit shows in the next mail. */
  private String shopName() {
    String name = shop == null ? null : shop.identity().name();
    return name == null || name.isBlank() ? FALLBACK_NAME : name;
  }

  @Override
  public void send(String email, String code) {
    SimpleMailMessage m = new SimpleMailMessage();
    m.setFrom(props.otp().from());
    m.setTo(email);
    m.setSubject("Your " + shopName() + " code: " + code);
    m.setText("Your sign-in code is " + code + ". It is valid for 5 minutes. Do not share it with anyone.");
    mail.send(m);
  }
}
