package com.vijay.mall.config;

import com.vijay.mall.common.cache.AppCaches;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Every setting the admin can change at run time. One list, so the admin screen, the validation and the code that uses a value
 * all agree. A value comes from the database if the admin set it, otherwise from the environment property named in
 * {@code envProperty} (so existing deployments keep working), otherwise from {@code fallback}.
 * Things the server needs before it can even open the database (database URL and password, the JWT secret, the OTP pepper, the key
 * that encrypts the secrets below) can NOT be here; see {@link BootstrapStatus}.
 */
public final class Settings {
  private Settings() {}

  /** How a setting is entered and validated. */
  public enum Type { STRING, SECRET, INT, BOOLEAN, ENUM }

  /** Sections of the config screen. */
  public enum Group {
    PAYMENT("Online payment"), PUSH("Push notifications"), SMS("SMS login codes"), EMAIL("E-mail login codes"),
    PAYTM("Paytm card machine"), LOGGING("Logging"), CACHE("Caching"), DELIVERY("Delivery"), COUNTER("Counter rules"), GST("GST and bills"), INVENTORY("Stock and lots"), SECURITY("Security"),
    COUPONS("Coupons and returns");

    /** The heading shown to the admin. */
    public final String label;

    Group(String label) { this.label = label; }
  }

  /**
   * @param allowed for ENUM: the only values accepted; min and max for INT (inclusive)
   * @param restart true when a change only takes effect after the server restarts (none today; kept so the screen can say so)
   */
  public record Def(String key, Group group, Type type, String label, String help, String envProperty, String fallback, List<String> allowed,
                    long min, long max, int maxLength, boolean restart) {
    /** Whether the value is a secret that is encrypted and never shown. */
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
  /** Razorpay key id. */
  public static final Def RAZORPAY_KEY_ID = text("razorpay.keyId", Group.PAYMENT, "Razorpay key id",
      "From the Razorpay dashboard > Settings > API keys (starts with rzp_).", "app.payment.razorpay-key-id", "", 100);
  /** Razorpay key secret (encrypted). */
  public static final Def RAZORPAY_KEY_SECRET = secret("razorpay.keySecret", Group.PAYMENT, "Razorpay key secret",
      "Shown once when the key is created. Leave blank to keep the saved one.", "app.payment.razorpay-key-secret", 200);
  /** Secret that signs Razorpay webhooks (encrypted). */
  public static final Def RAZORPAY_WEBHOOK_SECRET = secret("razorpay.webhookSecret", Group.PAYMENT, "Razorpay webhook secret",
      "The secret you typed when adding the webhook at Razorpay. Webhook address: https://<your-domain>/api/v1/payments/webhook/razorpay "
          + "(events payment.captured and payment.failed).", "app.payment.razorpay-webhook-secret", 200);
  /** Minutes a customer has to pay an online order before it is cancelled (5 to 120). */
  public static final Def ONLINE_TIMEOUT_MINUTES = number("payment.onlineTimeoutMinutes", Group.PAYMENT, "Minutes to pay an online order",
      "An online order nobody pays for in this time is cancelled.", "app.payment.online-timeout-minutes", "15", 5, 120);

  // ---- Paytm wireless card machine
  /** Master switch for the Paytm card machine. */
  public static final Def PAYTM_ENABLED = flag("paytm.enabled", Group.PAYTM, "Use the Paytm card machine",
      "Needs the merchant id, merchant key and channel id below. It cannot be switched on while one of them is missing.", "app.paytm.enabled", "false");
  /** STAGING or PRODUCTION. */
  public static final Def PAYTM_ENVIRONMENT = choice("paytm.environment", Group.PAYTM, "Paytm environment",
      "STAGING = Paytm's test system (no real money). PRODUCTION = live.", "app.paytm.environment", "STAGING", "STAGING", "PRODUCTION");
  /** Optional override of the Paytm host. */
  public static final Def PAYTM_BASE_URL = text("paytm.baseUrl", Group.PAYTM, "Paytm address (optional)",
      "Leave blank to use Paytm's standard address for the environment above. Fill it only if Paytm gave you another host. Must start with https://.",
      "app.paytm.base-url", "", 300);
  /** Paytm merchant id. */
  public static final Def PAYTM_MID = text("paytm.mid", Group.PAYTM, "Paytm merchant id (MID)", "From the Paytm dashboard or your Paytm welcome mail.",
      "app.paytm.mid", "", 40);
  /** Paytm merchant key (encrypted). */
  public static final Def PAYTM_MERCHANT_KEY = secret("paytm.merchantKey", Group.PAYTM, "Paytm merchant key",
      "The 16-character key from the Paytm dashboard. Leave blank to keep the saved one.", "app.paytm.merchant-key", 64);
  /** Channel id issued by Paytm for the wireless machine API. */
  public static final Def PAYTM_CHANNEL_ID = text("paytm.channelId", Group.PAYTM, "Paytm channel id", "Issued by Paytm when wireless payments are enabled for you.",
      "app.paytm.channel-id", "", 40);
  /** Seconds between status checks. */
  public static final Def PAYTM_POLL_SECONDS = number("paytm.pollSeconds", Group.PAYTM, "Seconds between payment checks",
      "How often an open card payment is checked with Paytm (Paytm advises about 10).", "app.paytm.poll-seconds", "10", 3, 60);
  /** Seconds to wait for the customer. */
  public static final Def PAYTM_WAIT_SECONDS = number("paytm.waitSeconds", Group.PAYTM, "Seconds to wait for the customer",
      "After this long an unpaid card payment is closed as expired (after one last check).", "app.paytm.wait-seconds", "180", 30, 900);
  /** Whether the machine accepts the amount without the cashier touching it. */
  public static final Def PAYTM_AUTO_ACCEPT = flag("paytm.autoAccept", Group.PAYTM, "Machine accepts the amount by itself",
      "On: the customer goes straight to paying. Off: the cashier presses accept on the machine first.", "app.paytm.auto-accept", "true");
  /** Which ways the machine offers. */
  public static final Def PAYTM_PAYMENT_MODE = choice("paytm.paymentMode", Group.PAYTM, "Ways to pay on the machine",
      "ALL = card and QR. CARD = card only. QR = QR only.", "app.paytm.payment-mode", "ALL", "ALL", "CARD", "QR");
  /** Time-out sent to Paytm with the sale; 0 leaves it out. */
  public static final Def PAYTM_TIMEOUT_CONFIG = number("paytm.timeoutConfig", Group.PAYTM, "Paytm time-out value (0 = not sent)",
      "Paytm accepts 1 to 60 here but its documentation gives no unit. Leave 0 unless Paytm tells you what to use.", "app.paytm.timeout-config", "0", 0, 60);
  /** Whether the settlement import from the Paytm API is on. */
  public static final Def PAYTM_SETTLEMENT_ENABLED = flag("paytm.settlement.enabled", Group.PAYTM, "Read settlements from the Paytm API",
      "Untested until Paytm gives API access. The dashboard Settlement Report file can always be imported instead.", "app.paytm.settlement.enabled", "false");
  /** How the settlement API is authenticated. */
  public static final Def PAYTM_SETTLEMENT_AUTH = choice("paytm.settlement.authStyle", Group.PAYTM, "Settlement API sign-in style",
      "CHECKSUM = signed with the merchant key. BEARER = a token from Paytm. Ask Paytm which one your account uses.",
      "app.paytm.settlement.auth-style", "BEARER", "CHECKSUM", "BEARER");
  /** Bearer token for the settlement API (encrypted). */
  public static final Def PAYTM_SETTLEMENT_TOKEN = secret("paytm.settlement.token", Group.PAYTM, "Settlement API token",
      "Only for the BEARER style. Leave blank to keep the saved one.", "app.paytm.settlement.token", 2000);
  /** Address of the secure settlement host. */
  public static final Def PAYTM_SETTLEMENT_BASE_URL = text("paytm.settlement.baseUrl", Group.PAYTM, "Settlement API address (optional)",
      "Leave blank for the standard production address. Must start with https://.", "app.paytm.settlement.base-url", "", 300);
  /** How many days back each settlement sync reads. */
  public static final Def PAYTM_SETTLEMENT_SYNC_DAYS = number("paytm.settlement.syncDays", Group.PAYTM, "Settlement days to read each time",
      "Each sync asks Paytm for this many days back, so late settlements are caught.", "app.paytm.settlement.sync-days", "7", 1, 60);
  /** The money account that receives online payments (blank = the active GATEWAY account whose provider matches the payment's provider). */
  public static final Def PAYMENT_GATEWAY_ACCOUNT = text("payment.gatewayAccountId", Group.PAYMENT, "Account for online payments",
      "Id of the money account online payments are received in. Blank = the active gateway account named after the provider (for example razorpay).",
      "app.payment.gateway-account-id", "", 40);
  /** Which configured gateway customers pay with (blank = the server's configured one). Several gateways may be set up at once. */
  public static final Def ONLINE_PROVIDER = text("payment.onlineProvider", Group.PAYMENT, "Gateway used for online orders",
      "Provider code of the gateway the customer app uses, for example razorpay. Blank = the server's default. Every set-up gateway keeps receiving webhooks.",
      "app.payment.online-provider-choice", "", 40);
  /** Days a provider normally takes to settle a captured payment. */
  public static final Def SETTLEMENT_DAYS = number("payment.settlementDays", Group.PAYMENT, "Settlement cycle (days)",
      "A captured payment still unsettled after this many days (plus one day of grace) is listed as overdue.",
      "app.payment.settlement-days", "2", 0, 30);

  // ---- push (Firebase Cloud Messaging)
  /** Firebase service account JSON used for push notifications (encrypted). */
  public static final Def FCM_SERVICE_ACCOUNT = secret("fcm.serviceAccount", Group.PUSH, "Firebase service account (JSON)",
      "Firebase console > Project settings > Service accounts > Generate new private key. Paste the whole JSON file.",
      "app.fcm.service-account", 8000);

  // ---- SMS login codes
  /** How login codes are sent by SMS: {@code none}, {@code console} or {@code http}. */
  public static final Def SMS_CHANNEL = choice("sms.channel", Group.SMS, "SMS sending", "none = mobile login is switched off; "
      + "console = codes are only written to the server log (testing); http = sent through your SMS provider.", "app.otp.sms-channel", "none",
      "none", "console", "http");
  /** Address of the SMS provider's send API. */
  public static final Def SMS_HTTP_URL = text("sms.http.url", Group.SMS, "SMS provider address (URL)",
      "The provider's send-SMS web address, from their documentation.", "app.sms.http.url", "", 500);
  /** HTTP method for the SMS provider call. */
  public static final Def SMS_HTTP_METHOD = choice("sms.http.method", Group.SMS, "Request method", "Almost always POST.",
      "app.sms.http.method", "POST", "POST", "GET");
  /** JSON object of headers for the SMS provider, usually the API key (encrypted). */
  public static final Def SMS_HTTP_HEADERS = secret("sms.http.headers", Group.SMS, "Provider headers (JSON)",
      "Usually the API key, for example {\"authkey\":\"abc123\"}. Kept secret.", "app.sms.http.headers", 2000);
  /** Request body template with placeholders for mobile, code and message. */
  public static final Def SMS_HTTP_BODY = text("sms.http.body", Group.SMS, "Request body template",
      "Placeholders: {mobile} {otp} {message} {senderId} {dltTemplateId}. For GET, put them in the URL instead.", "app.sms.http.body",
      "{\"mobile\":\"91{mobile}\",\"message\":\"{message}\",\"sender\":\"{senderId}\",\"template_id\":\"{dltTemplateId}\"}", 2000);
  /** Registered sender id for SMS. */
  public static final Def SMS_SENDER_ID = text("sms.senderId", Group.SMS, "Sender id", "Your 6-letter DLT registered sender id.",
      "app.sms.sender-id", "", 20);
  /** Registered DLT template id for SMS. */
  public static final Def SMS_DLT_TEMPLATE_ID = text("sms.dltTemplateId", Group.SMS, "DLT template id",
      "Indian operators only deliver messages that match a registered template.", "app.sms.dlt-template-id", "", 40);
  /** Text of the login SMS; must match the registered template. */
  public static final Def SMS_MESSAGE = text("sms.message", Group.SMS, "Message text", "Must match your registered DLT template. {otp} is the code.",
      "app.sms.message", "Your Vijay Super Mall login code is {otp}. It is valid for 5 minutes. Do not share it with anyone.", 300);

  // ---- e-mail login codes
  /** How login codes are sent by e-mail: {@code console} or {@code email}. */
  public static final Def OTP_CHANNEL = choice("otp.channel", Group.EMAIL, "E-mail sending", "email = codes are e-mailed; console = only written "
      + "to the server log (testing).", "app.otp.channel", "console", "console", "email");
  /** SMTP server for login e-mails. */
  public static final Def SMTP_HOST = text("smtp.host", Group.EMAIL, "SMTP server", "For example smtp.gmail.com", "spring.mail.host", "localhost", 200);
  /** SMTP port. */
  public static final Def SMTP_PORT = number("smtp.port", Group.EMAIL, "SMTP port", "587 for most providers.", "spring.mail.port", "587", 1, 65535);
  /** SMTP user name. */
  public static final Def SMTP_USER = text("smtp.user", Group.EMAIL, "SMTP user name", "", "spring.mail.username", "", 200);
  /** SMTP password (encrypted). */
  public static final Def SMTP_PASSWORD = secret("smtp.password", Group.EMAIL, "SMTP password", "Leave blank to keep the saved one.",
      "spring.mail.password", 200);
  /** Sender address of login e-mails. */
  public static final Def OTP_FROM = text("otp.from", Group.EMAIL, "From address", "Shown to the customer as the sender.", "app.otp.from",
      "no-reply@vijaysupermall.example", 200);

  // ---- logging
  /** Log level of the shop's own code. */
  public static final Def LOG_LEVEL_APP = choice("log.level.app", Group.LOGGING, "Shop code log detail", "DEBUG writes a lot more; use it "
      + "while chasing a problem, then go back to INFO.", "logging.level.com.vijay.mall", "INFO", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");
  /** Log level of libraries. */
  public static final Def LOG_LEVEL_ROOT = choice("log.level.root", Group.LOGGING, "Library log detail", "Spring, database driver and "
      + "other libraries.", "logging.level.root", "INFO", "ERROR", "WARN", "INFO", "DEBUG");
  /** Whether SQL statements are logged ({@code OFF} or {@code DEBUG}). */
  public static final Def LOG_LEVEL_SQL = choice("log.level.sql", Group.LOGGING, "Log database statements", "DEBUG shows every SQL statement: "
      + "very noisy, for short investigations only.", "app.log.sql", "OFF", "OFF", "DEBUG");
  /** Days rotated log files are kept (7, 15 or 30). */
  public static final Def LOG_RETENTION_DAYS = choice("log.retentionDays", Group.LOGGING, "Keep log files for (days)", "Older rotated files are "
      + "deleted. Logs are rolled every day.", "logging.logback.rollingpolicy.max-history", "30", "7", "15", "30");

  // ---- caching
  /** Master switch for the in-memory caches. */
  public static final Def CACHE_ENABLED = flag("cache.enabled", Group.CACHE, "Use caching", "Turn off to make every request read the database "
      + "(slower, but always the very latest). Use 'Refresh caches' to refresh once without turning caching off.", "app.cache.enabled", "true");

  // ---- delivery
  /** Delivery fee in paise; 0 means free delivery. */
  public static final Def DELIVERY_FEE = number("delivery.feePaise", Group.DELIVERY, "Delivery fee (paise)", "0 = free delivery. 4000 = Rs 40.",
      "app.delivery.fee-paise", "0", 0, 1_000_000);
  /** Order value in paise from which delivery is free. */
  public static final Def DELIVERY_FREE_ABOVE = number("delivery.freeAbovePaise", Group.DELIVERY, "Free delivery above (paise)",
      "Orders of this value or more pay no delivery fee. Only matters when the fee is above 0.", "app.delivery.free-above-paise", "49900", 0, 100_000_000);

  // ---- counter
  /** Days after a bill during which a cashier may take a return alone; 0 means no limit. */
  public static final Def POS_CASHIER_RETURN_DAYS = number("pos.cashierReturnDays", Group.COUNTER, "Days a cashier may take returns",
      "Older bills need an admin. 0 = no limit.", "app.pos.cashier-return-days", "7", 0, 365);
  /** Largest manual discount a cashier may give without a manager's PIN, in percent. */
  public static final Def POS_CASHIER_DISCOUNT_PERCENT = number("pos.cashierDiscountPercent", Group.COUNTER, "Discount a cashier may give alone (%)",
      "Above this a manager's PIN is needed. 0 = every manual discount needs the PIN.", "app.pos.cashier-discount-percent", "0", 0, 100);
  /** Whether a cashier must open a shift before billing. */
  public static final Def POS_REQUIRE_OPEN_SHIFT = flag("pos.requireOpenShift", Group.COUNTER, "Cashier must open a shift to bill",
      "Forces the drawer to be counted in before billing.", "app.pos.require-open-shift", "false");

  // ---- coupons and returns
  /** Whether a coupon also discounts items that are on a flash sale. */
  public static final Def COUPON_ALLOW_ON_FLASH = flag("coupon.allowOnFlashItems", Group.COUPONS, "Coupons on flash sale items",
      "On: a coupon also takes its discount off items that are on a flash sale (on top of the sale price). Off: flash sale items are left "
          + "out of every coupon, they do not count towards its minimum order and get no share of the discount.",
      "app.coupon.allow-on-flash-items", "true");
  /** What happens to the coupon discount when part of an order is returned. */
  public static final Def RETURNS_COUPON_POLICY = choice("returns.couponPolicy", Group.COUPONS, "Coupon when part of an order is returned",
      "KEEP: the customer keeps the coupon discount on the items they keep, in proportion, even if what is left is below the coupon's "
          + "minimum order. CLAWBACK_BELOW_MINIMUM: if what is left of the covered items falls below the coupon's minimum order, the "
          + "coupon discount on the items kept is taken back from the refund of the returned items (never below zero).",
      "app.returns.coupon-policy", "KEEP", "KEEP", "CLAWBACK_BELOW_MINIMUM");
  /** Money account used when a cash line names none. */
  public static final Def POS_DEFAULT_ACCOUNT_CASH = text("pos.defaultAccount.CASH", Group.COUNTER, "Default account for cash",
      "Id of the money account cash goes to when the till does not say. Blank = the cash drawer.", "app.pos.default-account.cash", "", 40);
  /** Money account used when a UPI line names none. */
  public static final Def POS_DEFAULT_ACCOUNT_UPI = text("pos.defaultAccount.UPI", Group.COUNTER, "Default account for UPI",
      "Id of the money account counter UPI goes to when the till does not say. Blank = not assigned.", "app.pos.default-account.upi", "", 40);
  /** Money account used when a card line names none. */
  public static final Def POS_DEFAULT_ACCOUNT_CARD = text("pos.defaultAccount.CARD", Group.COUNTER, "Default account for cards",
      "Id of the money account card payments go to when the till does not say. Blank = not assigned.", "app.pos.default-account.card", "", 40);
  /** Which card terminal adapter the till uses. */
  public static final Def POS_TERMINAL_PROVIDER = text("pos.terminalProvider", Group.COUNTER, "Card terminal adapter",
      "manual = the cashier completes the payment on the machine and types the approval code. A brand adapter name is added here when it is built.",
      "app.pos.terminal-provider", "manual", 40);

  // ---- GST and bills
  /** Whether counter bill totals are rounded to the nearest rupee. */
  public static final Def GST_ROUND_OFF = choice("gst.roundOff", Group.GST, "Round counter bills", "NEAREST_RUPEE rounds the bill total to the "
      + "nearest rupee and prints the round-off on the receipt. Your GSTIN, state code, invoice prefix and receipt footer are in Shop settings.",
      "app.gst.round-off", "NONE", "NONE", "NEAREST_RUPEE");

  /** How GST on supplier purchases is treated when the admin does not choose at purchase time. */
  public static final Def PURCHASE_GST_MODE = choice("purchase.gstMode", Group.GST, "GST on purchases",
      "REGULAR: GST paid to suppliers is claimed back as input credit and left out of the product cost. COMPOSITION: no credit, all GST is part of "
          + "the cost. NONE: purchases carry no GST. The admin can still pick another mode on each purchase.",
      "app.purchase.gst-mode", "REGULAR", "REGULAR", "COMPOSITION", "NONE");

  // ---- stock lots
  /**
   * What an online order does when it needs more units than remain in the current lot of a lot-priced item. Only CAP exists today:
   * the quote refuses the excess ("only N available at this price"). SPLIT (price per lot into separate order lines) is not
   * supported yet and is refused as a value.
   */
  public static final Def INVENTORY_LOT_CROSS_POLICY = choice("inventory.lotCrossPolicy", Group.INVENTORY, "Online orders across lots",
      "CAP: an online order cannot take more units than are left at the current price of a lot-priced item; the customer is told how many "
          + "are available at this price. (SPLIT, pricing the order per lot, is not supported yet.)", "app.inventory.lot-cross-policy", "CAP", "CAP");

  /** How the cost of goods sold is worked out from stock lots. */
  public static final Def INVENTORY_COSTING_METHOD = choice("inventory.costingMethod", Group.INVENTORY, "Costing method",
      "FIFO: a sale takes the cost of the oldest lot (earliest expiry first for items that track expiry). Weighted average may be added later.",
      "app.inventory.costing-method", "FIFO", "FIFO");

  /** Days ahead the daily expiry alert looks (1 to 365). */
  public static final Def INVENTORY_EXPIRY_ALERT_DAYS = number("inventory.expiryAlertDays", Group.INVENTORY, "Expiry alert window (days)",
      "Every day at 03:15 staff are told about lots that expire within this many days.", "app.inventory.expiry-alert-days", "30", 1, 365);

  /** Whether lots past their expiry date are written off by the daily job. */
  public static final Def INVENTORY_AUTO_WRITE_OFF = flag("inventory.autoWriteOffExpired", Group.INVENTORY, "Write off expired lots automatically",
      "When on, the daily job at 03:15 takes lots past their expiry date out of stock (history reason EXPIRY_WRITE_OFF). Off by default: staff decide.",
      "app.inventory.auto-write-off-expired", "false");

  // ---- security
  /** Name of the proxy header that carries the real client address, used by the rate limiter. */
  public static final Def CLIENT_IP_HEADER = text("security.clientIpHeader", Group.SECURITY, "Real client address header",
      "Set to CF-Connecting-IP when behind Cloudflare so limits apply per customer, not per proxy. Only safe if the server accepts traffic "
          + "from the proxy alone.", "app.rate-limit.client-ip-header", "", 60);

  /** How long each cache keeps its data, in seconds (0 = do not keep). One setting per cache, defaulting to the cache's own time. */
  private static List<Def> cacheTtls() {
    List<Def> out = new ArrayList<>();
    for (AppCaches.Name n : AppCaches.Name.values()) {
      out.add(number("cache.ttl." + n.key, Group.CACHE, "Keep '" + n.key + "' for (seconds)", n.description + ". 0 = do not keep.",
          "app.cache.ttl." + n.key, Integer.toString(n.ttlSeconds), 0, 3600));
    }
    return out;
  }

  private static final List<Def> CACHE_TTLS = cacheTtls();

  /** The setting that says how long the given cache keeps its data. */
  public static Def ttlOf(AppCaches.Name name) {
    return CACHE_TTLS.get(name.ordinal());
  }

  private static final List<Def> BASE = List.of(RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET, RAZORPAY_WEBHOOK_SECRET, ONLINE_TIMEOUT_MINUTES,
      PAYTM_ENABLED, PAYTM_ENVIRONMENT, PAYTM_BASE_URL, PAYTM_MID, PAYTM_MERCHANT_KEY, PAYTM_CHANNEL_ID, PAYTM_POLL_SECONDS,
      PAYTM_WAIT_SECONDS, PAYTM_AUTO_ACCEPT, PAYTM_PAYMENT_MODE, PAYTM_TIMEOUT_CONFIG, PAYTM_SETTLEMENT_ENABLED, PAYTM_SETTLEMENT_AUTH,
      PAYTM_SETTLEMENT_TOKEN, PAYTM_SETTLEMENT_BASE_URL, PAYTM_SETTLEMENT_SYNC_DAYS,
      PAYMENT_GATEWAY_ACCOUNT, ONLINE_PROVIDER, SETTLEMENT_DAYS, POS_DEFAULT_ACCOUNT_CASH, POS_DEFAULT_ACCOUNT_UPI, POS_DEFAULT_ACCOUNT_CARD, POS_TERMINAL_PROVIDER,
      FCM_SERVICE_ACCOUNT, SMS_CHANNEL, SMS_HTTP_URL, SMS_HTTP_METHOD, SMS_HTTP_HEADERS, SMS_HTTP_BODY, SMS_SENDER_ID, SMS_DLT_TEMPLATE_ID,
      SMS_MESSAGE, OTP_CHANNEL, SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, OTP_FROM, LOG_LEVEL_APP, LOG_LEVEL_ROOT, LOG_LEVEL_SQL,
      LOG_RETENTION_DAYS, DELIVERY_FEE, DELIVERY_FREE_ABOVE, POS_CASHIER_RETURN_DAYS, POS_CASHIER_DISCOUNT_PERCENT, POS_REQUIRE_OPEN_SHIFT,
      GST_ROUND_OFF, PURCHASE_GST_MODE, INVENTORY_LOT_CROSS_POLICY, INVENTORY_COSTING_METHOD, INVENTORY_EXPIRY_ALERT_DAYS, INVENTORY_AUTO_WRITE_OFF,
      CLIENT_IP_HEADER, CACHE_ENABLED, COUPON_ALLOW_ON_FLASH, RETURNS_COUPON_POLICY);

  /** Every setting: the fixed ones followed by one cache lifetime per cache. */
  public static final List<Def> ALL = Stream.concat(BASE.stream(), CACHE_TTLS.stream()).toList();

  private static final Map<String, Def> BY_KEY = ALL.stream().collect(Collectors.toMap(Def::key, Function.identity()));

  /** The setting with this key, or {@code null} if there is none. */
  public static Def find(String key) { return BY_KEY.get(key); }
}
