package com.vijay.mall.ops;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The metrics endpoint is open to whoever can reach its port (Prometheus has no login), so it must live on its own private
 * port. This refuses to start a real (non dev/test) server where metrics would share the public port.
 */
@Component
@Profile("!dev & !test")
public class ManagementPortGuard {
  /**
   * Checks the ports at start-up and aborts with {@code IllegalStateException} when metrics would share the public port.
   */
  public ManagementPortGuard(Environment env) {
    String problem = problem(env.getProperty("management.server.port"), env.getProperty("server.port", "8080"));
    if (problem != null) throw new IllegalStateException("Unsafe configuration: " + problem);
  }

  /** Null when fine. Pure, so it is unit tested. */
  public static String problem(String managementPort, String serverPort) {
    if (managementPort == null || managementPort.isBlank() || managementPort.trim().equals(serverPort == null ? "8080" : serverPort.trim())) {
      return "MANAGEMENT_PORT must be a different, private port from the public one, or metrics would be readable by anyone";
    }
    return null;
  }
}
