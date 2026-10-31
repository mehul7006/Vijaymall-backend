package com.vijay.mall.identity.app;

/** Port: delivers a one-time code. Adapters: console (dev) and email. */
public interface OtpSender {
  void send(String email, String code);
}
