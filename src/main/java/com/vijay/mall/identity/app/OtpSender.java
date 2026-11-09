package com.vijay.mall.identity.app;

/** Port: delivers a one-time code. Adapters: console (dev) and email. */
public interface OtpSender {
  /**
   * Delivers a one-time code to an e-mail address.
   * @param email the recipient
   * @param code the clear code, valid for 5 minutes
   */
  void send(String email, String code);
}
