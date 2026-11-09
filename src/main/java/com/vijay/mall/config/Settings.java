package com.vijay.mall.config;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every setting the admin can change at run time. One list, so the admin screen, the validation and the code that uses a value
 * all agree. A value comes from the database if the admin set it, otherwise from the environment property named in
 * {@code envProperty} (so existing deployments keep working), otherwise from {@code fallback}.
 * Things the server needs before it can even open the database (database URL and password, the JWT secret, the OTP pepper, the key
 * that encrypts the secrets below) can NOT be here; see {@link BootstrapStatus}.
 */
public final class Settings {
  private Settings() {}

  public enum Type { STRING, SECRET, INT, BOOLEAN, ENUM }

  public enum Group {
    PAYMENT("Online payment"), PUSH("Push notifications"), SMS("SMS login codes"), EMAIL("E-mail login codes"),
    LOGGING("Logging"), CACHE("Caching"), DELIVERY("Delivery"), COUNTER("Counter rules"), GST("GST and bills"), SECURITY("Security");

    public final String label;

    Group(String label) { this.label = label; }
  }

  /**
   * @param allowed for ENUM: the only values accepted; min and max for INT (inclusive)
   * @param restart true when a change only takes effect after the server restarts (none today; kept so the screen can say so)
   */
  public record Def(String key, Group group, Type type, String label, String help, String envProperty, String fallback, List<String> allowed,
                    long min, long max, int maxLength, boolean restart) {
    public boolean secret() { return type == Type.SECRET; }
  }

  private static Def text(String key, Group g, String label, String help, String env, String fallback, int maxLength) {
    return new Def(key, g, Type.STRING, label, help, env, fallback, List.of(), 0, 0, maxLength, false);
  }

  private static Def secret(String key, Group g, String label, String help, String env, int maxLength) {
    return new Def(key, g, Type.SECRET, label, help, env, "", List.of(), 0, 0, maxLength, false);
  }

  private static Def number(String key, Group g, String label, String help, String env, String fallback, long min, long max) {
    return new Def(key, g, Type.INT, label, help, env, fallback, List.of(), min, max, 20, false);
  }

  private static Def flag(String key, Group g, String label, String help, String env, String fallback) {
    return new Def(key, g, Type.BOOLEAN, label, help, env, fallback, List.of("true", "false"), 0, 0, 5, false);
  }

  private static Def choice(String key, Group g, String label, String help, String env, String fallback, String... allowed) {
    return new Def(key, g, Type.ENUM, label, help, env, fallback, List.of(allowed), 0, 0, 40, false);
  }

  // ---- online payment (Razorpay)
  public static final Def RAZORPAY_KEY_ID = text("razorpay.keyId", Group.PAYMENT, "Razorpay key id",
      "From the Razorpay dashboard > Settings > API keys (starts with rzp_).", "app.payment.razorpay-key-id", "", 100);
  public static final Def RAZORPAY_KEY_SECRET = secret("razorpay.keySecret", Group.PAYMENT, "Razorpay key secret",
      "Shown once when the key is created. Leave blank to keep the saved one.", "app.payment.razorpay-key-secret", 200);
  public static final Def RAZORPAY_WEBHOOK_SECRET = secret("razorpay.webhookSecret", Group.PAYMENT, "Razorpay webhook secret",
      "The secret you typed when adding the webhook at Razorpay. Webhook address: https://<your-domain>/api/v1/payments/webhook/razorpay "
          + "(events payment.captured and payment.failed).", "app.payment.razorpay-webhook-secret", 200);
  public static final Def ONLINE_TIMEOUT_MINUTES = number("payment.onlineTimeoutMinutes", Group.PAYMENT, "Minutes to pay an online order",
      "An online order nobody pays for in this time is cancelled.", "app.payment.online-timeout-minutes", "15", 5, 120);

  // ---- push (Firebase Cloud Messaging)
  public static final Def FCM_SERVICE_ACCOUNT = secret("fcm.serviceAccount", Group.PUSH, "Firebase service account (JSON)",
      "Firebase console > Project settings > Service accounts > Generate new private key. Paste the whole JSON file.",
      "app.fcm.service-account", 8000);

  // ---- SMS login codes
  public static final Def SMS_CHANNEL = choice("sms.channel", Group.SMS, "SMS sending", "none = mobile login is switched off; "
      + "console = codes are only written to the server log (testing); http = sent through your SMS provider.", "app.otp.sms-channel", "none",
      "none", "console", "http");
  public static final Def SMS_HTTP_URL = text("sms.http.url", Group.SMS, "SMS provider address (URL)",
      "The provider's send-SMS web address, from their documentation.", "app.sms.http.url", "", 500);
  public static final Def SMS_HTTP_METHOD = choice("sms.http.method", Group.SMS, "Request method", "Almost always POST.",
      "app.sms.http.method", "POST", "POST", "GET");
  public static final Def SMS_HTTP_HEADERS = secret("sms.http.headers", Group.SMS, "Provider headers (JSON)",
      "Usually the API key, for example {\"authkey\":\"abc123\"}. Kept secret.", "app.sms.http.headers", 2000);
  public static final Def SMS_HTTP_BODY = text("sms.http.body", Group.SMS, "Request body template",
      "Placeholders: {mobile} {otp} {message} {senderId} {dltTemplateId}. For GET, put them in the URL instead.", "app.sms.http.body",
      "{\"mobile\":\"91{mobile}\",\"message\":\"{message}\",\"sender\":\"{senderId}\",\"template_id\":\"{dltTemplateId}\"}", 2000);
  public static final Def SMS_SENDER_ID = text("sms.senderId", Group.SMS, "Sender id", "Your 6-letter DLT registered sender id.",
      "app.sms.sender-id", "", 20);
  public static final Def SMS_DLT_TEMPLATE_ID = text("sms.dltTemplateId", Group.SMS, "DLT template id",
      "Indian operators only deliver messages that match a registered template.", "app.sms.dlt-template-id", "", 40);
  public static final Def SMS_MESSAGE = text("sms.message", Group.SMS, "Message text", "Must match your registered DLT template. {otp} is the code.",
      "app.sms.message", "Your Vijay Super Mall login code is {otp}. It is valid for 5 minutes. Do not share it with anyone.", 300);

  // ---- e-mail login codes
  public static final Def OTP_CHANNEL = choice("otp.channel", Group.EMAIL, "E-mail sending", "email = codes are e-mailed; console = only written "
      + "to the server log (testing).", "app.otp.channel", "console", "console", "email");
  public static final Def SMTP_HOST = text("smtp.host", Group.EMAIL, "SMTP server", "For example smtp.gmail.com", "spring.mail.host", "localhost", 200);
  public static final Def SMTP_PORT = number("smtp.port", Group.EMAIL, "SMTP port", "587 for most providers.", "spring.mail.port", "587", 1, 65535);
  public static final Def SMTP_USER = text("smtp.user", Group.EMAIL, "SMTP user name", "", "spring.mail.username", "", 200);
  public static final Def SMTP_PASSWORD = secret("smtp.password", Group.EMAIL, "SMTP password", "Leave blank to keep the saved one.",
      "spring.mail.password", 200);
  public static final Def OTP_FROM = text("otp.from", Group.EMAIL, "From address", "Shown to the customer as the sender.", "app.otp.from",
      "no-reply@vijaysupermall.example", 200);

  // ---- logging
  public static final Def LOG_LEVEL_APP = choice("log.level.app", Group.LOGGING, "Shop code log detail", "DEBUG writes a lot more; use it "
      + "while chasing a problem, then go back to INFO.", "logging.level.com.vijay.mall", "INFO", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");
  public static final Def LOG_LEVEL_ROOT = choice("log.level.root", Group.LOGGING, "Library log detail", "Spring, database driver and "
      + "other libraries.", "logging.level.root", "INFO", "ERROR", "WARN", "INFO", "DEBUG");
  public static final Def LOG_LEVEL_SQL = choice("log.level.sql", Group.LOGGING, "Log database statements", "DEBUG shows every SQL statement: "
      + "very noisy, for short investigations only.", "app.log.sql", "OFF", "OFF", "DEBUG");
  public static final Def LOG_RETENTION_DAYS = choice("log.retentionDays", Group.LOGGING, "Keep log files for (days)", "Older rotated files are "
      + "deleted. Logs are rolled every day.", "logging.logback.rollingpolicy.max-history", "30", "7", "15", "30");

  // ---- caching
  public static final Def CACHE_ENABLED = flag("cache.enabled", Group.CACHE, "Use caching", "Turn off to make every request read the database "
      + "(slower, but always the very latest). Use 'Refresh caches' to refresh once without turning caching off.", "app.cache.enabled", "true");

  // ---- delivery
  public static final Def DELIVERY_FEE = number("delivery.feePaise", Group.DELIVERY, "Delivery fee (paise)", "0 = free delivery. 4000 = Rs 40.",
      "app.delivery.fee-paise", "0", 0, 1_000_000);
  public static final Def DELIVERY_FREE_ABOVE = number("delivery.freeAbovePaise", Group.DELIVERY, "Free delivery above (paise)",
      "Orders of this value or more pay no delivery fee. Only matters when the fee is above 0.", "app.delivery.free-above-paise", "49900", 0, 100_000_000);

  // ---- counter
  public static final Def POS_CASHIER_RETURN_DAYS = number("pos.cashierReturnDays", Group.COUNTER, "Days a cashier may take returns",
      "Older bills need an admin. 0 = no limit.", "app.pos.cashier-return-days", "7", 0, 365);
  public static final Def POS_CASHIER_DISCOUNT_PERCENT = number("pos.cashierDiscountPercent", Group.COUNTER, "Discount a cashier may give alone (%)",
      "Above this a manager's PIN is needed. 0 = every manual discount needs the PIN.", "app.pos.cashier-discount-percent", "0", 0, 100);
  public static final Def POS_REQUIRE_OPEN_SHIFT = flag("pos.requireOpenShift", Group.COUNTER, "Cashier must open a shift to bill",
      "Forces the drawer to be counted in before billing.", "app.pos.require-open-shift", "false");

  // ---- GST and bills
  public static final Def GST_ROUND_OFF = choice("gst.roundOff", Group.GST, "Round counter bills", "NEAREST_RUPEE rounds the bill total to the "
      + "nearest rupee and prints the round-off on the receipt. Your GSTIN, state code, invoice prefix and receipt footer are in Shop settings.",
      "app.gst.round-off", "NONE", "NONE", "NEAREST_RUPEE");

  // ---- security
  public static final Def CLIENT_IP_HEADER = text("security.clientIpHeader", Group.SECURITY, "Real client address header",
      "Set to CF-Connecting-IP when behind Cloudflare so limits apply per customer, not per proxy. Only safe if the server accepts traffic "
          + "from the proxy alone.", "app.rate-limit.client-ip-header", "", 60);

  /** How long each cache keeps its data, in seconds (0 = do not keep). One setting per cache, defaulting to the cache's own time. */
  private static List<Def> cacheTtls() {
    List<Def> out = new java.util.ArrayList<>();
    for (com.vijay.mall.common.cache.AppCaches.Name n : com.vijay.mall.common.cache.AppCaches.Name.values()) {
      out.add(number("cache.ttl." + n.key, Group.CACHE, "Keep '" + n.key + "' for (seconds)", n.description + ". 0 = do not keep.",
          "app.cache.ttl." + n.key, Integer.toString(n.ttlSeconds), 0, 3600));
    }
    return out;
  }

  private static final List<Def> CACHE_TTLS = cacheTtls();

  /** The setting that says how long the given cache keeps its data. */
  public static Def ttlOf(com.vijay.mall.common.cache.AppCaches.Name name) {
    return CACHE_TTLS.get(name.ordinal());
  }

  private static final List<Def> BASE = List.of(RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET, ONLINE_TIMEOUT_MINUTES,
      FCM_SERVICE_ACCOUNT, SMS_CHANNEL, SMS_HTTP_URL, SMS_HTTP_METHOD, SMS_HTTP_HEADERS, SMS_HTTP_BODY, SMS_SENDER_ID, SMS_DLT_TEMPLATE_ID,
      SMS_MESSAGE, OTP_CHANNEL, SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, OTP_FROM, LOG_LEVEL_APP, LOG_LEVEL_ROOT, LOG_LEVEL_SQL,
      LOG_RETENTION_DAYS, DELIVERY_FEE, DELIVERY_FREE_ABOVE, POS_CASHIER_RETURN_DAYS, POS_CASHIER_DISCOUNT_PERCENT, POS_REQUIRE_OPEN_SHIFT,
      GST_ROUND_OFF, CLIENT_IP_HEADER, CACHE_ENABLED);

  public static final List<Def> ALL = java.util.stream.Stream.concat(BASE.stream(), CACHE_TTLS.stream()).toList();

  private static final Map<String, Def> BY_KEY = ALL.stream().collect(Collectors.toMap(Def::key, Function.identity()));

  public static Def find(String key) { return BY_KEY.get(key); }
}
