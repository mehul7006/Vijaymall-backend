package com.vijay.mall.config;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.Settings.Def;
import com.vijay.mall.config.Settings.Type;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The settings the admin can change at run time. Reading is from memory (refreshed every 30 seconds and right after a save), so
 * asking for a setting is free and a change in one server shows in the others within half a minute. The value is the one the
 * admin saved; if none, the environment property the server was started with; if none, the built-in default. Secrets are
 * encrypted in the database and are never returned to any screen: the screen only learns whether one is set.
 */
@Service
public class RuntimeSettings implements MeterBinder {
  private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

  /**
   * Where the value in force comes from: the admin's saved value, the server's environment, or the built-in default.
   */
  public enum Source { DATABASE, ENVIRONMENT, DEFAULT }

  /** What the admin screen shows for one setting. value is null for secrets (use {@code set}). */
  @Schema(name = "SettingRow")
  public record Row(String key, String group, String groupLabel, String type, String label, String help, boolean secret, boolean set,
                    String value, String source, List<String> allowed, Long min, Long max, boolean restart) {}

  /**
   * One requested change.
   * @param key the setting key, see {@link Settings}
   * @param value the new value; blank clears the setting
   */
  @Schema(name = "SettingChange")
  public record Change(String key, String value) {}

  private final AppSettingJpa jpa;
  private final ConfigCrypto crypto;
  private final Environment env;
  private final AuditService audit;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final ApplicationEventPublisher events;
  private final JsonMapper json;

  private volatile MeterRegistry registry;

  /** Gauge of half-finished set-ups, so a dashboard and an alert can say "someone left the payment keys half entered". */
  @Override
  public void bindTo(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder("mall.config.issues", this, s -> s.issues().size())
        .description("Configuration problems shown at the top of the admin config screen").register(registry);
  }

  private volatile Map<String, String> stored = Map.of();
  private volatile Set<String> undecryptable = Set.of();
  private volatile boolean loaded;

  /** Creates the service. */
  public RuntimeSettings(AppSettingJpa jpa, ConfigCrypto crypto, Environment env, AuditService audit, Clock clock, TransactionTemplate tx,
                         ApplicationEventPublisher events, JsonMapper json) {
    this.jpa = jpa;
    this.crypto = crypto;
    this.env = env;
    this.audit = audit;
    this.clock = clock;
    this.tx = tx;
    this.events = events;
    this.json = json;
  }

  // ---------- reading ----------
  private void ensureLoaded() {
    if (!loaded) refresh();
  }

  /** Reloads from the database. Keeps the previous values if the database cannot be read. */
  @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
  public void refresh() {
    try {
      Map<String, String> fresh = new HashMap<>();
      Set<String> bad = new HashSet<>();
      for (SettingEntity e : jpa.findAll()) {
        Def d = Settings.find(e.name);
        if (d == null) continue; // a setting this version no longer knows
        if (e.secret) {
          try {
            fresh.put(e.name, crypto.decrypt(e.value));
          } catch (IllegalStateException ex) {
            bad.add(e.name);
            log.error("Setting {} cannot be decrypted: {}", e.name, ex.getMessage());
          }
        } else {
          fresh.put(e.name, e.value);
        }
      }
      stored = Map.copyOf(fresh);
      undecryptable = Set.copyOf(bad);
      loaded = true;
    } catch (RuntimeException e) {
      log.warn("Could not reload settings, keeping the current ones: {}", e.getMessage());
      loaded = true;
    }
  }

  /** The value in force; "" when nothing is set anywhere. */
  public String get(Def d) {
    ensureLoaded();
    String v = stored.get(d.key());
    // a "console" channel saved on a dev server must never take effect on a real one: codes would end up in the log
    if (v != null && "console".equals(v) && (d == Settings.OTP_CHANNEL || d == Settings.SMS_CHANNEL) && !consoleCodesAllowed()) {
      return d == Settings.OTP_CHANNEL ? "email" : "none";
    }
    if (v != null) return v;
    String fromEnv = env.getProperty(d.envProperty());
    if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
    return d.fallback() == null ? "" : d.fallback();
  }

  /** Whether the setting has a non-blank value from any source. */
  public boolean isSet(Def d) { return !get(d).isBlank(); }

  /** The value as a whole number; the built-in default when the value cannot be read. */
  public int getInt(Def d) {
    try {
      return Integer.parseInt(get(d).trim());
    } catch (NumberFormatException e) {
      return Integer.parseInt(d.fallback());
    }
  }

  /** The value as a long number; the built-in default when the value cannot be read. */
  public long getLong(Def d) {
    try {
      return Long.parseLong(get(d).trim());
    } catch (NumberFormatException e) {
      return Long.parseLong(d.fallback());
    }
  }

  /** The value as a boolean. */
  public boolean getBool(Def d) { return Boolean.parseBoolean(get(d).trim()); }

  /** Where the value in force comes from. */
  public Source sourceOf(Def d) {
    ensureLoaded();
    if (stored.containsKey(d.key())) return Source.DATABASE;
    String fromEnv = env.getProperty(d.envProperty());
    return fromEnv != null && !fromEnv.isBlank() ? Source.ENVIRONMENT : Source.DEFAULT;
  }

  // ---------- the admin screen ----------
  /** Every setting as shown on the admin screen; the value of a secret is never included. */
  public List<Row> view() {
    List<Row> rows = new ArrayList<>();
    for (Def d : Settings.ALL) {
      boolean set = isSet(d);
      rows.add(new Row(d.key(), d.group().name(), d.group().label, d.type().name(), d.label(), d.help(), d.secret(), set,
          d.secret() ? null : get(d), sourceOf(d).name(), d.allowed(), d.type() == Type.INT ? d.min() : null,
          d.type() == Type.INT ? d.max() : null, d.restart()));
    }
    return rows;
  }

  /** Things that are half set up and will not work, in plain words, for the top of the screen. */
  public List<String> issues() {
    ensureLoaded();
    List<String> out = new ArrayList<>();
    boolean keyId = isSet(Settings.RAZORPAY_KEY_ID);
    boolean secret = isSet(Settings.RAZORPAY_KEY_SECRET);
    if (keyId != secret) out.add("Razorpay needs both the key id and the key secret: online payment stays off until both are saved.");
    if (keyId && secret && !isSet(Settings.RAZORPAY_WEBHOOK_SECRET)) {
      out.add("Razorpay webhook secret is missing: payments are only confirmed by the 5-minute check, not instantly.");
    }
    if (getBool(Settings.PAYTM_ENABLED)) {
      List<String> missing = paytmMissing(Map.of());
      if (!missing.isEmpty()) out.add("The Paytm card machine is on but " + String.join(", ", missing) + " is missing: card payments cannot be sent.");
    }
    if ("http".equals(get(Settings.SMS_CHANNEL))) {
      if (!isSet(Settings.SMS_HTTP_URL)) out.add("SMS is set to your provider but the provider address is empty: mobile login cannot send codes.");
      if (!isSet(Settings.SMS_SENDER_ID) || !isSet(Settings.SMS_DLT_TEMPLATE_ID)) {
        out.add("SMS sender id or DLT template id is empty: Indian operators will not deliver the messages.");
      }
    }
    if (("console".equals(stored.get(Settings.OTP_CHANNEL.key())) || "console".equals(stored.get(Settings.SMS_CHANNEL.key()))) && !consoleCodesAllowed()) {
      out.add("Login codes in the server log are saved as the setting but ignored: this is not a dev server, so codes go by e-mail (and SMS only if set up).");
    }
    if ("email".equals(get(Settings.OTP_CHANNEL)) && "localhost".equals(get(Settings.SMTP_HOST))) {
      out.add("E-mail login codes are on but the SMTP server is still localhost: no e-mail will arrive.");
    }
    if (!undecryptable.isEmpty()) {
      out.add("These saved secrets could not be read (the encryption key changed?): " + String.join(", ", new TreeSet<>(undecryptable))
          + ". Enter them again.");
    }
    if (!crypto.available()) out.add("CONFIG_ENCRYPTION_KEY is not set on the server: secrets (keys, passwords) cannot be saved from this screen.");
    return out;
  }

  // ---------- saving ----------
  /**
   * Saves the given changes together or not at all. A blank value clears the setting (back to the environment / default). A
   * secret the admin does not mention stays as it is. Returns the keys that actually changed.
   */
  public Set<String> save(List<Change> changes, UUID actor) {
    if (changes == null || changes.isEmpty()) throw ApiException.validation("changes", "nothing to save");
    Map<String, String> clean = new LinkedHashMap<>();
    for (Change c : changes) {
      Def d = c.key() == null ? null : Settings.find(c.key());
      if (d == null) throw ApiException.validation("key", "unknown setting: " + c.key());
      if (clean.containsKey(d.key())) throw ApiException.validation("key", "listed twice: " + d.key());
      try {
        clean.put(d.key(), validate(d, c.value() == null ? "" : c.value()));
      } catch (ApiException e) {
        // the reason names the setting but never the value that was typed (it may be a secret)
        log.atWarn().setMessage("Config change refused: {}").addArgument(e.getMessage()).addKeyValue("event", "config-refused")
            .addKeyValue("key", d.key()).addKeyValue("actor", String.valueOf(actor)).log();
        throw e;
      }
    }
    paytmGuard(clean);
    if (clean.entrySet().stream().anyMatch(e -> !e.getValue().isEmpty() && Settings.find(e.getKey()).secret()) && !crypto.available()) {
      throw ApiException.unprocessable("CONFIG_KEY_MISSING", "The server has no CONFIG_ENCRYPTION_KEY, so secrets cannot be stored safely", Map.of());
    }
    Set<String> changed = new HashSet<>();
    Instant now = clock.instant();
    Consumer<TransactionStatus> apply = s -> clean.forEach((key, value) -> {
      Def d = Settings.find(key);
      SettingEntity old = jpa.findById(key).orElse(null);
      if (value.isEmpty()) {
        if (old != null) {
          jpa.delete(old);
          changed.add(key);
          audit.log(actor, "CONFIG_CLEAR", "app_setting", key, d.secret() ? "cleared" : "was " + shown(old.value));
        }
        return;
      }
      String before = old == null ? null : d.secret() ? "x" : old.value;
      String stored = d.secret() ? crypto.encrypt(value) : value;
      if (old != null && (d.secret() ? sameSecret(old.value, value) : old.value.equals(value))) return;
      SettingEntity e = old == null ? new SettingEntity() : old;
      e.name = key;
      e.value = stored;
      e.secret = d.secret();
      e.updatedAt = now;
      e.updatedBy = actor;
      jpa.save(e);
      changed.add(key);
      audit.log(actor, "CONFIG_CHANGE", "app_setting", key, d.secret() ? "changed" : (before == null ? "" : shown(before) + " -> ") + shown(value));
    });
    try {
      tx.executeWithoutResult(apply);
    } catch (DataIntegrityViolationException race) {
      // Two admins saved the same new setting at the same moment and both tried to insert it: the second one starts over
      // and now finds the row, so it updates it instead.
      changed.clear();
      tx.executeWithoutResult(apply);
    }
    if (!changed.isEmpty()) {
      for (String key : changed) {
        Def d = Settings.find(key);
        // a secret's value is never logged, and not even its length
        log.atInfo().setMessage("Config changed: {}{}").addArgument(key).addArgument(d.secret() ? " (secret)" : "")
            .addKeyValue("event", "config-change").addKeyValue("key", key).addKeyValue("group", d.group().name())
            .addKeyValue("secret", d.secret()).addKeyValue("actor", String.valueOf(actor)).log();
        MeterRegistry r = registry;
        if (r != null) r.counter("mall.config.changes", "group", d.group().name()).increment();
      }
      refresh();
      events.publishEvent(new SettingsChanged(Set.copyOf(changed)));
    }
    return changed;
  }

  /** The value a setting will have once {@code pending} is saved: a blank pending value means cleared (environment or default). */
  private String effective(Def d, Map<String, String> pending) {
    String v = pending.get(d.key());
    if (v == null) return get(d);
    if (!v.isEmpty()) return v;
    String fromEnv = env.getProperty(d.envProperty());
    return fromEnv != null && !fromEnv.isBlank() ? fromEnv : d.fallback() == null ? "" : d.fallback();
  }

  /** Names of the Paytm settings that are still empty after applying {@code pending}. */
  private List<String> paytmMissing(Map<String, String> pending) {
    List<String> missing = new ArrayList<>();
    for (Def d : List.of(Settings.PAYTM_MID, Settings.PAYTM_MERCHANT_KEY, Settings.PAYTM_CHANNEL_ID)) {
      if (effective(d, pending).isBlank()) missing.add(d.label());
    }
    return missing;
  }

  /** Refuses a save that would leave the Paytm card machine switched on without what it needs. */
  private void paytmGuard(Map<String, String> pending) {
    if (!Boolean.parseBoolean(effective(Settings.PAYTM_ENABLED, pending).trim())) return;
    List<String> missing = paytmMissing(pending);
    if (!missing.isEmpty()) {
      throw ApiException.validation(Settings.PAYTM_ENABLED.key(), "The Paytm card machine cannot be on while these are empty: " + String.join(", ", missing));
    }
  }

  private boolean sameSecret(String storedCipher, String plain) {
    try {
      return crypto.decrypt(storedCipher).equals(plain);
    } catch (IllegalStateException e) {
      return false;
    }
  }

  private static String shown(String v) { return v.length() > 120 ? v.substring(0, 120) + "..." : v; }

  /** Returns the value in its saved form, or throws a 400 saying what is wrong. A blank value means "clear" and is always fine. */
  String validate(Def d, String raw) {
    String v = raw.strip();
    if (v.isEmpty()) return "";
    if (v.length() > d.maxLength()) throw bad(d, "is too long (at most " + d.maxLength() + " characters)");
    for (int i = 0; i < v.length(); i++) {
      char ch = v.charAt(i);
      if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t') throw bad(d, "contains a character that is not allowed");
    }
    switch (d.type()) {
      case INT -> {
        long n;
        try {
          n = Long.parseLong(v);
        } catch (NumberFormatException e) {
          throw bad(d, "must be a whole number");
        }
        if (n < d.min() || n > d.max()) throw bad(d, "must be between " + d.min() + " and " + d.max());
        return Long.toString(n);
      }
      case BOOLEAN, ENUM -> {
        String chosen = d.allowed().stream().filter(a -> a.equalsIgnoreCase(v)).findFirst()
            .orElseThrow(() -> bad(d, "must be one of: " + String.join(", ", d.allowed())));
        if ((d == Settings.OTP_CHANNEL || d == Settings.SMS_CHANNEL) && "console".equals(chosen) && !consoleCodesAllowed()) {
          throw bad(d, "console (login codes written to the server log) exists only in the dev profile: use " + (d == Settings.OTP_CHANNEL ? "email" : "http or none"));
        }
        return chosen;
      }
      default -> { /* text and secrets: checked below */ }
    }
    special(d, v);
    return v;
  }

  private void special(Def d, String v) {
    if (d == Settings.SMS_HTTP_URL) {
      String lower = v.toLowerCase();
      boolean local = lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1");
      if (!lower.startsWith("https://") && !local) throw bad(d, "must start with https://");
    } else if (d == Settings.SMS_HTTP_HEADERS) {
      JsonNode n = parse(d, v);
      if (!n.isObject()) throw bad(d, "must be a JSON object like {\"authkey\":\"...\"}");
    } else if (d == Settings.FCM_SERVICE_ACCOUNT) {
      JsonNode n = parse(d, v);
      for (String f : List.of("project_id", "client_email", "private_key")) {
        if (!n.hasNonNull(f) || n.path(f).asString("").isBlank()) throw bad(d, "is not a service account file: \"" + f + "\" is missing");
      }
    } else if (d == Settings.RAZORPAY_KEY_ID && !v.startsWith("rzp_")) {
      throw bad(d, "should start with rzp_");
    } else if (d == Settings.PAYTM_BASE_URL || d == Settings.PAYTM_SETTLEMENT_BASE_URL) {
      String lower = v.toLowerCase();
      boolean local = lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1");
      if (!lower.startsWith("https://") && !local) throw bad(d, "must start with https://");
    } else if (d == Settings.PAYTM_MERCHANT_KEY && v.length() != 16) {
      throw bad(d, "must be exactly 16 characters");
    } else if ((d == Settings.PAYTM_MID || d == Settings.PAYTM_CHANNEL_ID) && !v.matches("[A-Za-z0-9_-]+")) {
      throw bad(d, "may contain only letters, digits, - and _");
    } else if (d == Settings.CLIENT_IP_HEADER && !v.matches("[A-Za-z0-9-]+")) {
      throw bad(d, "must be a header name like CF-Connecting-IP");
    }
  }

  /** Whether this server may write login codes to its log: only the dev and test profiles. A real server never does. */
  boolean consoleCodesAllowed() {
    return env.acceptsProfiles(Profiles.of("dev", "test"));
  }

  private JsonNode parse(Def d, String v) {
    try {
      return json.readTree(v);
    } catch (RuntimeException e) {
      throw bad(d, "is not valid JSON");
    }
  }

  private static ApiException bad(Def d, String why) { return ApiException.validation(d.key(), d.label() + " " + why); }
}
