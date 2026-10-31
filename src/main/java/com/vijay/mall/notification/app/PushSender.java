package com.vijay.mall.notification.app;

import java.util.List;

/** Port for sending a push message. FCM can be added later as another implementation. */
public interface PushSender {
  void send(List<String> deviceTokens, String title, String body);
}
