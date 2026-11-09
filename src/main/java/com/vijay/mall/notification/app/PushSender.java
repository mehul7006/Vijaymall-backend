package com.vijay.mall.notification.app;

import java.util.List;

/** Port for sending a push message. FCM can be added later as another implementation. */
public interface PushSender {
  /**
   * Sends one push message to several devices. Implementations must not throw: a failed push never breaks the business flow.
   * @param deviceTokens push tokens of the target devices
   * @param title headline
   * @param body message text
   */
  void send(List<String> deviceTokens, String title, String body);

  /**
   * True when messages really reach a push provider; false for a stand-in that only logs. Broadcast progress counts pushed devices only
   * when this is true, and the admin screen shows it.
   */
  default boolean isConfigured() { return true; }
}
