package com.vijay.mall.identity.infra;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.ConnectionTester;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import java.security.SecureRandom;
import java.util.Map;
import org.springframework.stereotype.Component;

/** "Test this setting" for SMS: sends a real test message to the given mobile through the saved provider. */
@Component
public class SmsConnectionTester implements ConnectionTester {
  private static final SecureRandom RANDOM = new SecureRandom();

  private final RuntimeSettings settings;
  private final HttpSmsSender sender;

  /** Creates the tester with the live settings and the HTTP SMS sender (wired by Spring). */
  public SmsConnectionTester(RuntimeSettings settings, HttpSmsSender sender) {
    this.settings = settings;
    this.sender = sender;
  }

  @Override
  public String target() { return "sms"; }

  @Override
  public Result test(Map<String, String> args) {
    try {
      if (!"http".equalsIgnoreCase(settings.get(Settings.SMS_CHANNEL).trim())) {
        return new Result(false, "SMS sending is not set to your provider. Choose \"http\", save, then test.");
      }
      if (!settings.isSet(Settings.SMS_HTTP_URL)) return new Result(false, "The provider address (URL) is empty. Save it first.");
      String mobile = args == null ? "" : args.getOrDefault("mobile", "").trim();
      if (!mobile.matches("[0-9]{10}")) return new Result(false, "Enter the 10-digit mobile number to send the test message to.");
      sender.send(mobile, String.format("%06d", RANDOM.nextInt(1_000_000)));
      return new Result(true, "The provider accepted the test message for " + HttpSmsSender.mask(mobile) + ". Check that it arrives.");
    } catch (ApiException e) {
      return new Result(false, "The provider did not accept the test message (" + e.code() + "). Check the address, headers and body; "
          + "see the server log for the status.");
    } catch (RuntimeException e) {
      return new Result(false, "The test could not be sent: " + e.getClass().getSimpleName());
    }
  }
}
