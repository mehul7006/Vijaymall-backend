package com.vijay.mall.identity.infra;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the SMS through whatever HTTP provider the admin described in the settings (address, method, headers, body template).
 * The code and the provider headers (the API key) are never logged.
 */
@Component
public class HttpSmsSender implements com.vijay.mall.identity.app.SmsOtpSender {
  private static final Logger log = LoggerFactory.getLogger(HttpSmsSender.class);
  private static final String PROVIDER_ERROR = "SMS_PROVIDER_ERROR";
  private static final String SAFE_MESSAGE = "The code could not be sent by SMS right now. Please try again or use email.";

  private final RuntimeSettings settings;
  private final JsonMapper json;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();

  public HttpSmsSender(RuntimeSettings settings, JsonMapper json) {
    this.settings = settings;
    this.json = json;
  }

  @Override
  public void send(String mobile, String code) {
    String url = settings.get(Settings.SMS_HTTP_URL).trim();
    if (url.isEmpty()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_NOT_CONFIGURED", "Mobile sign-in is not available right now. Please use email.");
    }
    boolean get = "GET".equalsIgnoreCase(settings.get(Settings.SMS_HTTP_METHOD).trim());
    String message = settings.get(Settings.SMS_MESSAGE).replace("{otp}", code);
    Map<String, String> values = Map.of("{mobile}", mobile, "{otp}", code, "{message}", message,
        "{senderId}", settings.get(Settings.SMS_SENDER_ID), "{dltTemplateId}", settings.get(Settings.SMS_DLT_TEMPLATE_ID));

    int status;
    try {
      HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(fill(url, values, false))).timeout(Duration.ofSeconds(10));
      boolean contentType = addHeaders(rb);
      if (get) {
        rb.GET();
      } else {
        if (!contentType) rb.header("Content-Type", "application/json");
        rb.method("POST", HttpRequest.BodyPublishers.ofString(fill(settings.get(Settings.SMS_HTTP_BODY), values, true), StandardCharsets.UTF_8));
      }
      status = client.send(rb.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      log.warn("SMS provider not reachable: {}", e.getClass().getSimpleName());
      throw failure();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("SMS sending interrupted");
      throw failure();
    } catch (IllegalArgumentException | SecurityException e) {
      log.warn("SMS provider settings are not usable: {}", e.getClass().getSimpleName());
      throw failure();
    }
    if (status / 100 != 2) {
      log.warn("SMS provider refused the message status={}", status);
      throw failure();
    }
    log.info("SMS sent to {} status={}", mask(mobile), status);
  }

  private static ApiException failure() { return new ApiException(HttpStatus.BAD_GATEWAY, PROVIDER_ERROR, SAFE_MESSAGE); }

  /** Adds the saved headers; returns whether one of them is a Content-Type. */
  private boolean addHeaders(HttpRequest.Builder rb) {
    String raw = settings.get(Settings.SMS_HTTP_HEADERS).trim();
    if (raw.isEmpty()) return false;
    JsonNode n;
    try {
      n = json.readTree(raw);
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalArgumentException("headers");
    }
    if (!n.isObject()) throw new IllegalArgumentException("headers");
    boolean contentType = false;
    for (Map.Entry<String, JsonNode> e : n.properties()) {
      rb.header(e.getKey(), e.getValue().asString(""));
      contentType |= "content-type".equalsIgnoreCase(e.getKey());
    }
    return contentType;
  }

  private String fill(String template, Map<String, String> values, boolean jsonText) {
    String out = template;
    for (Map.Entry<String, String> e : values.entrySet()) {
      out = out.replace(e.getKey(), jsonText ? jsonEscape(e.getValue()) : URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
    }
    return out;
  }

  private String jsonEscape(String v) {
    String quoted = json.writeValueAsString(v);
    return quoted.substring(1, quoted.length() - 1);
  }

  /** 9876543210 -> 98******10 */
  static String mask(String mobile) {
    if (mobile == null || mobile.length() <= 4) return "****";
    return mobile.substring(0, 2) + "*".repeat(mobile.length() - 4) + mobile.substring(mobile.length() - 2);
  }
}
