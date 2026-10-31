package com.vijay.mall.identity.infra;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.identity.app.OtpSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Sends the code by email through the SMTP server in spring.mail.*. */
@Component
@ConditionalOnProperty(name = "app.otp.channel", havingValue = "email")
public class SmtpEmailOtpSender implements OtpSender {
  private final JavaMailSender mail;
  private final AppProps props;

  public SmtpEmailOtpSender(JavaMailSender mail, AppProps props) {
    this.mail = mail;
    this.props = props;
  }

  @Override
  public void send(String email, String code) {
    SimpleMailMessage m = new SimpleMailMessage();
    m.setFrom(props.otp().from());
    m.setTo(email);
    m.setSubject("Your Vijay Super Mall code: " + code);
    m.setText("Your sign-in code is " + code + ". It is valid for 5 minutes. Do not share it with anyone.");
    mail.send(m);
  }
}
