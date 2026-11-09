package com.vijay.mall.notification.infra;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.notification.app.PushSender;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Sends through Firebase when a service account is saved, otherwise only writes to the log. Decided on every call, so a save takes effect at once. */
@Primary
@Component
public class PushSenderRouter implements PushSender {
  private final RuntimeSettings settings;
  private final FcmPushSender fcm;
  private final LoggingPushSender logging;

  /** Creates the router with the live settings and both senders (wired by Spring). */
  public PushSenderRouter(RuntimeSettings settings, FcmPushSender fcm, LoggingPushSender logging) {
    this.settings = settings;
    this.fcm = fcm;
    this.logging = logging;
  }

  @Override
  public boolean isConfigured() { return settings.isSet(Settings.FCM_SERVICE_ACCOUNT); }

  @Override
  public void send(List<String> deviceTokens, String title, String body, Map<String, String> data) {
    (isConfigured() ? fcm : logging).send(deviceTokens, title, body, data);
  }
}
