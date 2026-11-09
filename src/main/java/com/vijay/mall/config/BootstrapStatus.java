package com.vijay.mall.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * The few settings the server needs BEFORE it can read the settings table: the database address and password, the key that signs
 * sign-ins, the OTP pepper, the key that encrypts the saved secrets. They cannot be edited from the app (the app could not even open
 * the database to read them), so they stay in the server's environment / secret manager. This shows, without ever revealing a value,
 * whether each one is properly set, so nothing quietly runs on a default.
 */
@Service
public class BootstrapStatus {
  public enum State { OK, DEFAULT, MISSING, UNSAFE }

  public record Item(String name, String setBy, State state, String detail) {}

  private final Environment env;
  private final ConfigCrypto crypto;
  private final String imagesDir;

  public BootstrapStatus(Environment env, ConfigCrypto crypto, @Value("${app.images-dir:./data/images}") String imagesDir) {
    this.env = env;
    this.crypto = crypto;
    this.imagesDir = imagesDir;
  }

  private String p(String key) {
    String v = env.getProperty(key);
    return v == null ? "" : v;
  }

  public List<Item> items() {
    List<Item> out = new ArrayList<>();
    String url = p("spring.datasource.url");
    String host = url.replaceAll("^jdbc:postgresql://", "").replaceAll("[/?].*$", "");
    out.add(new Item("Database address", "DB_URL", url.isBlank() ? State.MISSING : url.contains("localhost") ? State.DEFAULT : State.OK,
        url.isBlank() ? "not set" : "server " + host));
    boolean dbPassDefault = "mall".equals(p("spring.datasource.password"));
    out.add(new Item("Database user and password", "DB_USER, DB_PASSWORD", dbPassDefault ? State.UNSAFE : State.OK,
        dbPassDefault ? "still the built-in development password" : "set"));
    String jwt = p("app.jwt.secret");
    boolean jwtWeak = jwt.startsWith("change-me") || jwt.length() < 32;
    out.add(new Item("Sign-in signing secret", "JWT_SECRET", jwt.isBlank() ? State.MISSING : jwtWeak ? State.UNSAFE : State.OK,
        jwt.startsWith("change-me") ? "still the public default: anyone could forge a sign-in" : "set"));
    String pepper = p("app.otp.pepper");
    boolean pepperWeak = pepper.startsWith("dev-") || pepper.length() < 16;
    out.add(new Item("Login code pepper", "OTP_PEPPER", pepper.isBlank() ? State.MISSING : pepperWeak ? State.UNSAFE : State.OK,
        pepper.startsWith("dev-") ? "still the development default" : "set"));
    boolean fixed = !p("app.otp.fixed-code").isBlank();
    out.add(new Item("Fixed login code", "OTP_FIXED_CODE", fixed ? State.UNSAFE : State.OK,
        fixed ? "ON: every login code is the same. Never in production" : "off (codes are random)"));
    out.add(new Item("Secrets encryption key", "CONFIG_ENCRYPTION_KEY", crypto.available() ? State.OK : State.MISSING,
        crypto.available() ? "set" : "not set: keys and passwords cannot be saved from the config screen"));
    String mgmt = p("management.server.port");
    boolean shared = mgmt.isBlank() || mgmt.equals(p("server.port"));
    out.add(new Item("Private metrics port", "MANAGEMENT_PORT", shared ? State.UNSAFE : State.OK,
        mgmt.isBlank() ? "metrics would share the public port" : "port " + mgmt + " (keep it off the internet)"));
    out.add(new Item("Admin e-mail (first admin)", "ADMIN_EMAIL", p("app.bootstrap-admin-email").isBlank() ? State.MISSING : State.OK,
        p("app.bootstrap-admin-email").isBlank() ? "not set" : "set"));
    out.add(new Item("Product image folder", "IMAGES_DIR", State.OK, imagesDir + " (use a persistent disk)"));
    String[] profiles = env.getActiveProfiles();
    out.add(new Item("Server mode", "SPRING_PROFILES_ACTIVE", profiles.length == 0 ? State.DEFAULT : State.OK,
        profiles.length == 0 ? "no profile" : String.join(",", profiles)));
    return out;
  }
}
