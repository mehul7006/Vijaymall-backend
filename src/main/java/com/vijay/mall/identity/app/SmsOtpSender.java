package com.vijay.mall.identity.app;

/**
 * Port: delivers a one-time code to a mobile number by SMS. Adapters: console (dev) and "not configured".
 * To go live, add one class implementing this interface for your SMS provider (India needs a DLT-registered template)
 * and select it with app.otp.sms-channel.
 */
public interface SmsOtpSender {
  /**
   * Delivers a one-time code to a mobile number by SMS.
   * @param mobile the 10-digit recipient number
   * @param code the clear code, valid for 5 minutes
   */
  void send(String mobile, String code);
}
