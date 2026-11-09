package com.vijay.mall.config;

import java.util.Map;

/** "Test this setting" for the admin screen: tries the saved credentials for real and says plainly whether they work. */
public interface ConnectionTester {
  /**
   * Outcome of a test.
   * @param ok whether the credentials worked
   * @param message a plain explanation for the admin; never contains a secret
   */
  record Result(boolean ok, String message) {}

  /** The name used in the address: /api/v1/admin/config/test/{target}. */
  String target();

  /** args are optional extras from the screen (for example the mobile number or e-mail to send a test to). Never throws. */
  Result test(Map<String, String> args);
}
