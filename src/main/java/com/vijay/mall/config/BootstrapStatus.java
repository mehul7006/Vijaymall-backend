package com.vijay.mall.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * The few settings the server needs BEFORE it can read the settings table: the database address and password, the key that signs
 * sign-ins, the OTP pepper, the key that encrypts the saved secrets. They cannot be edited from the app (the app could not even open
 * the database to read them), so they stay in the server's environment / secret manager. This shows, without ever revealing a value,
 * whether each one is properly set, so nothing quietly runs on a default.
 */
@Service
public class BootstrapStatus {
  /**
   * How a start-up setting looks: {@code OK}, {@code DEFAULT} (a development default), {@code MISSING}, or {@code UNSAFE} (set to a known weak value).
   */
  public enum State { OK, DEFAULT, MISSING, UNSAFE }

  /**
   * One start-up setting.
   * @param name what it is, in plain words
   * @param setBy the environment variable(s) that set it
   * @param state how it looks
   * @param detail a short note that never contains the value
   */
  public record Item(String name, String setBy, State state, String detail) {}

  private final Environment env;
  private final ConfigCrypto crypto;
  private final String imagesDir;

  /** Creates the service. */
  public BootstrapStatus(Environment env, ConfigCrypto crypto, @Value("${app.images-dir:./data/images}") String imagesDir) {
    this.env = env;
    this.crypto = crypto;
    this.imagesDir = imagesDir;
  }

  private String p(String key) {
    String v = env.getProperty(key);
    return v == null ? "" : v;
  }

  /**
   * Checks the database address and password, JWT secret, OTP pepper, fixed login code, encryption key, management port, first-admin e-mail, image folder and active profile.
   */
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
    boolean consoleCodes = "console".equals(p("app.otp.channel")) || "console".equals(p("app.otp.sms-channel"));
    boolean devOrTest = env.acceptsProfiles(Profiles.of("dev", "test"));
    out.add(new Item("Login codes in the server log", "OTP_CHANNEL, SMS_CHANNEL", consoleCodes && !devOrTest ? State.UNSAFE : State.OK,
        !consoleCodes ? "off (codes are sent by e-mail or SMS)"
            : devOrTest ? "on (dev server: codes are printed in the log)" : "not allowed here: the server refuses to start with it outside the dev profile"));
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
