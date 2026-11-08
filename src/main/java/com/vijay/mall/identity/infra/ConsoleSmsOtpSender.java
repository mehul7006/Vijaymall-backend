package com.vijay.mall.identity.infra;

import com.vijay.mall.identity.app.SmsOtpSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Dev adapter: prints the code in the log. Chosen by RoutingSmsOtpSender when SMS sending is set to console. */
@Component
public class ConsoleSmsOtpSender implements SmsOtpSender {
  private static final Logger log = LoggerFactory.getLogger(ConsoleSmsOtpSender.class);

  @Override
  public void send(String mobile, String code) {
    log.info("SMS OTP for {} is {}", mobile, code);
  }
}
