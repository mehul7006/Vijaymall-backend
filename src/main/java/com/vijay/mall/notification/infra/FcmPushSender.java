package com.vijay.mall.notification.infra;

import com.vijay.mall.config.ConnectionTester;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.notification.app.PushSender;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends push messages through Firebase Cloud Messaging (HTTP v1) using the service account the admin saved. Sending never throws:
 * a failure is logged (never the device token or the key) and the business flow carries on. Tokens Firebase calls dead are removed.
 */
@Component
public class FcmPushSender implements PushSender {
  private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
  static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private static final String DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token";
  private static final int PARALLEL = 8;
  private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

  private record Account(String raw, String projectId, String email, PrivateKey key, String tokenUri) {}

  private final RuntimeSettings settings;
  private final DeviceRepository devices;
  private final JsonMapper json;
  private final Clock clock;
  private final String baseUrl;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  /** guarded by this */
  private Account account;
  /** guarded by this */
  private String accessToken;
  /** guarded by this */
  private Instant refreshAt;

  public FcmPushSender(RuntimeSettings settings, DeviceRepository devices, JsonMapper json, Clock clock,
                       @Value("${app.fcm.base-url:https://fcm.googleapis.com}") String baseUrl) {
    this.settings = settings;
    this.devices = devices;
    this.json = json;
    this.clock = clock;
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
  }

  @Override
  public void send(List<String> deviceTokens, String title, String body) {
    try {
      if (deviceTokens == null || deviceTokens.isEmpty()) return;
      Account a = account();
      String bearer = bearer(a);
      String t = cut(title, 100);
      String b = cut(body, 400);
      Semaphore gate = new Semaphore(PARALLEL);
      try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
        for (String token : deviceTokens) {
          pool.submit(() -> {
            gate.acquireUninterruptibly();
            try {
              sendOne(a, bearer, token, t, b);
            } finally {
              gate.release();
            }
          });
        }
      }
    } catch (RuntimeException e) {
      log.warn("Push not sent: {}", e.getMessage());
    }
  }

  private void sendOne(Account a, String bearer, String token, String title, String body) {
    try {
      Map<String, Object> message = new LinkedHashMap<>();
      message.put("token", token);
      message.put("notification", Map.of("title", title, "body", body));
      message.put("android", Map.of("priority", "HIGH"));
      HttpResponse<String> r = call(sendRequest(a, bearer, Map.of("message", message)));
      int code = r.statusCode();
      if (code / 100 == 2) return;
      String status = errorStatus(r.body());
      if (code == 404 || "UNREGISTERED".equals(status) || "INVALID_ARGUMENT".equals(status)) {
        devices.deleteToken(token);
        log.info("Removed a dead push token (FCM said {} {})", code, status);
        return;
      }
      if (code == 401) dropAccessToken();
      log.warn("FCM did not accept a push: HTTP {} {}", code, status);
    } catch (RuntimeException e) {
      log.warn("A push could not be sent: {}", e.getMessage());
    }
  }

  /** Used by the "test this setting" button. Never throws. */
  ConnectionTester.Result validate() {
    try {
      Account a = account();
      String bearer = bearer(a);
      Map<String, Object> message = Map.of("token", "validate-only-check-token", "notification", Map.of("title", "test", "body", "test"));
      Map<String, Object> req = Map.of("validate_only", true, "message", message);
      int code = call(sendRequest(a, bearer, req)).statusCode();
      if (code / 100 == 2 || code == 400 || code == 404) {
        // 400/404 only complain about the fake device token, which proves the project and the account were accepted.
        return new ConnectionTester.Result(true, "Firebase accepted the service account for project " + a.projectId() + ".");
      }
      if (code == 401) dropAccessToken();
      return new ConnectionTester.Result(false, "Firebase signed in but would not send for project " + a.projectId() + " (HTTP " + code
          + "). Check that the Firebase Cloud Messaging API is enabled and the account may send messages.");
    } catch (RuntimeException e) {
      return new ConnectionTester.Result(false, "Firebase refused it: " + e.getMessage());
    }
  }

  // ---------- requests ----------
  private HttpRequest sendRequest(Account a, String bearer, Map<String, Object> payload) {
    return HttpRequest.newBuilder(URI.create(baseUrl + "/v1/projects/" + a.projectId() + "/messages:send"))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + bearer)
        .header("Content-Type", "application/json; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
        .build();
  }

  private HttpResponse<String> call(HttpRequest request) {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new IllegalStateException("could not reach Firebase (" + e.getClass().getSimpleName() + ")");
    }
  }

  private String errorStatus(String body) {
    try {
      return json.readTree(body).path("error").path("status").asString("");
    } catch (RuntimeException e) {
      return "";
    }
  }

  // ---------- service account and access token ----------
  private synchronized Account account() {
    String raw = settings.get(Settings.FCM_SERVICE_ACCOUNT);
    if (raw.isBlank()) throw new IllegalStateException("no Firebase service account is saved");
    if (account == null || !account.raw().equals(raw)) {
      account = parse(raw);
      accessToken = null; // the setting changed: the old token belongs to the old account
    }
    return account;
  }

  private Account parse(String raw) {
    JsonNode n;
    try {
      n = json.readTree(raw);
    } catch (RuntimeException e) {
      throw new IllegalStateException("the service account is not valid JSON");
    }
    for (String f : List.of("project_id", "client_email", "private_key")) {
      if (n.path(f).asString("").isBlank()) throw new IllegalStateException("the service account has no \"" + f + "\"");
    }
    String pem = n.path("private_key").asString("")
        .replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
    PrivateKey key;
    try {
      key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalStateException("the private key in the service account cannot be read");
    }
    return new Account(raw, n.path("project_id").asString(""), n.path("client_email").asString(""), key,
        n.path("token_uri").asString(DEFAULT_TOKEN_URI));
  }

  private synchronized void dropAccessToken() { accessToken = null; }

  private synchronized String bearer(Account a) {
    if (accessToken != null && clock.instant().isBefore(refreshAt)) return accessToken;
    Instant now = clock.instant();
    HttpRequest request = HttpRequest.newBuilder(URI.create(a.tokenUri()))
        .timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
            + "&assertion=" + URLEncoder.encode(jwt(a, now), StandardCharsets.UTF_8)))
        .build();
    HttpResponse<String> r = call(request);
    if (r.statusCode() != 200) {
      String why = errorText(r.body());
      throw new IllegalStateException("Google refused the service account (HTTP " + r.statusCode() + why + ")");
    }
    JsonNode n;
    try {
      n = json.readTree(r.body());
    } catch (RuntimeException e) {
      n = json.createObjectNode();
    }
    String token = n.path("access_token").asString("");
    if (token.isBlank()) throw new IllegalStateException("Google sign-in gave no access token");
    long seconds = n.path("expires_in").asLong(3600);
    accessToken = token;
    refreshAt = now.plusSeconds(seconds).minus(REFRESH_MARGIN);
    return token;
  }

  private String errorText(String body) {
    try {
      String e = json.readTree(body).path("error").asString("");
      return e.isBlank() ? "" : ": " + cut(e, 100);
    } catch (RuntimeException e) {
      return "";
    }
  }

  private String jwt(Account a, Instant now) {
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", a.email());
    claims.put("scope", SCOPE);
    claims.put("aud", a.tokenUri());
    claims.put("iat", now.getEpochSecond());
    claims.put("exp", now.getEpochSecond() + 3600);
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    String head = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String payload = enc.encodeToString(json.writeValueAsBytes(claims));
    try {
      Signature s = Signature.getInstance("SHA256withRSA");
      s.initSign(a.key());
      s.update((head + "." + payload).getBytes(StandardCharsets.US_ASCII));
      return head + "." + payload + "." + enc.encodeToString(s.sign());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("the private key in the service account cannot sign");
    }
  }

  static String cut(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }
}
