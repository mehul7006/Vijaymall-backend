package com.vijay.mall.identity.infra;

import com.vijay.mall.identity.app.OtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dev adapter: prints the code in the log. Active unless app.otp.channel=email. */
@Component
@ConditionalOnProperty(name = "app.otp.channel", havingValue = "console", matchIfMissing = true)
public class ConsoleOtpSender implements OtpSender {
  private static final Logger log = LoggerFactory.getLogger(ConsoleOtpSender.class);

  @Override
  public void send(String email, String code) {
    log.info("OTP for {} is {}", email, code);
  }
}
