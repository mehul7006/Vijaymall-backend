package com.vijay.mall.identity.infra;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.app.SmsOtpSender;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Default: no SMS provider is wired, so mobile sign-in says so instead of pretending to send a code. */
@Component
public class UnconfiguredSmsOtpSender implements SmsOtpSender {
  @Override
  public void send(String mobile, String code) {
    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_NOT_CONFIGURED",
        "Mobile sign-in is not available right now. Please use email.");
  }
}
