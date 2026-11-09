package com.vijay.mall.notification.infra;

import com.vijay.mall.notification.app.PushSender;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stand-in until Firebase is set up: only writes the message to the log. */
@Component
public class LoggingPushSender implements PushSender {
  private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

  @Override
  public boolean isConfigured() { return false; }

  @Override
  public void send(List<String> deviceTokens, String title, String body, Map<String, String> data) {
    log.info("PUSH to {} device(s): {} - {} {}", deviceTokens.size(), title, body, data);
  }
}
