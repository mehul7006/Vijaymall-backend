package com.vijay.mall.payment.paytm.app;

import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/** The Paytm configuration as saved on the admin screen, read fresh on every call so a change applies without a restart. */
@Component
public class PaytmSettings {
  /**
   * Default hosts per environment. UNCONFIRMED: Paytm's pages show several hosts for the machine APIs, so the admin can override the
   * host with the "Paytm address" setting; confirm with Paytm before go-live.
   */
  static final String STAGING_HOST = "https://securestage.paytmpayments.com";
  static final String PRODUCTION_HOST = "https://secure.paytmpayments.com";
  /** Default host of the settlement APIs (production only). UNCONFIRMED. */
  static final String SETTLEMENT_HOST = "https://secure.paytmpayments.com";

  private final RuntimeSettings settings;

  /** Creates the reader. */
  public PaytmSettings(RuntimeSettings settings) { this.settings = settings; }

  /** Whether the admin switched the machine on. */
  public boolean enabled() { return settings.getBool(Settings.PAYTM_ENABLED); }

  /** The configured environment, {@code STAGING} or {@code PRODUCTION}. */
  public String environment() { return settings.get(Settings.PAYTM_ENVIRONMENT).toUpperCase(); }

  /** The host calls go to: the override when set, otherwise the default of the environment; no trailing slash. */
  public String baseUrl() {
    String override = settings.get(Settings.PAYTM_BASE_URL).strip();
    String url = !override.isEmpty() ? override : "PRODUCTION".equals(environment()) ? PRODUCTION_HOST : STAGING_HOST;
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /** Merchant id. */
  public String mid() { return settings.get(Settings.PAYTM_MID).strip(); }

  /** Merchant key (secret; never log it). */
  public String merchantKey() { return settings.get(Settings.PAYTM_MERCHANT_KEY); }

  /** Channel id. */
  public String channelId() { return settings.get(Settings.PAYTM_CHANNEL_ID).strip(); }

  /** Seconds between status checks. */
  public int pollSeconds() { return settings.getInt(Settings.PAYTM_POLL_SECONDS); }

  /** Seconds to wait for the customer. */
  public int waitSeconds() { return settings.getInt(Settings.PAYTM_WAIT_SECONDS); }

  /** Whether the machine accepts the amount by itself. */
  public boolean autoAccept() { return settings.getBool(Settings.PAYTM_AUTO_ACCEPT); }

  /** ALL, CARD or QR. */
  public String paymentMode() { return settings.get(Settings.PAYTM_PAYMENT_MODE).toUpperCase(); }

  /** Time-out value sent with a sale; 0 means not sent. */
  public int timeoutConfig() { return settings.getInt(Settings.PAYTM_TIMEOUT_CONFIG); }

  /** Whether settlements may be read from the Paytm API. */
  public boolean settlementEnabled() { return settings.getBool(Settings.PAYTM_SETTLEMENT_ENABLED); }

  /** BEARER or CHECKSUM. */
  public String settlementAuth() { return settings.get(Settings.PAYTM_SETTLEMENT_AUTH).toUpperCase(); }

  /** Bearer token of the settlement API (secret). */
  public String settlementToken() { return settings.get(Settings.PAYTM_SETTLEMENT_TOKEN); }

  /** Days back each settlement sync reads. */
  public int syncDays() { return settings.getInt(Settings.PAYTM_SETTLEMENT_SYNC_DAYS); }

  /** The settlement host: the override or the production default. */
  public String settlementBaseUrl() {
    String override = settings.get(Settings.PAYTM_SETTLEMENT_BASE_URL).strip();
    String url = override.isEmpty() ? SETTLEMENT_HOST : override;
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  /** The names of required settings that are empty. */
  public List<String> missing() {
    List<String> out = new ArrayList<>();
    if (mid().isEmpty()) out.add("merchant id");
    if (merchantKey().isBlank()) out.add("merchant key");
    if (channelId().isEmpty()) out.add("channel id");
    return out;
  }

  /** Whether card payments can be sent: switched on and complete. */
  public boolean ready() { return enabled() && missing().isEmpty(); }
}
