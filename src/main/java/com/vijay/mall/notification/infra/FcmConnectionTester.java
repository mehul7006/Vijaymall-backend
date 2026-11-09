package com.vijay.mall.notification.infra;

import com.vijay.mall.config.ConnectionTester;
import java.util.Map;
import org.springframework.stereotype.Component;

/** "Test" button for the Firebase service account: signs in and asks Firebase to validate (not deliver) a message. */
@Component
public class FcmConnectionTester implements ConnectionTester {
  private final FcmPushSender sender;

  /** Creates the tester with the Firebase sender (wired by Spring). */
  public FcmConnectionTester(FcmPushSender sender) { this.sender = sender; }

  @Override
  public String target() { return "fcm"; }

  @Override
  public Result test(Map<String, String> args) { return sender.validate(); }
}
