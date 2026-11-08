package com.vijay.mall.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.config.ConnectionTester;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.identity.infra.DeviceRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FcmPushSenderTest {
  static final JsonMapper JSON = JsonMapper.builder().build();
  static KeyPair keys;

  /** A clock the test can move. */
  static class MutableClock extends Clock {
    volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId z) { return this; }
    @Override public Instant instant() { return now; }
  }

  HttpServer server;
  MutableClock clock = new MutableClock();
  AtomicInteger tokenCalls = new AtomicInteger();
  List<String> assertions = new CopyOnWriteArrayList<>();
  List<String[]> sendCalls = new CopyOnWriteArrayList<>(); // {authorization, body}
  Map<String, Object[]> sendAnswers = new ConcurrentHashMap<>(); // device token -> {status, body}
  volatile int tokenStatus = 200;
  volatile String tokenBody = "{\"access_token\":\"AT-1\",\"expires_in\":3600}";
  volatile String lastTokenRequest;
  AtomicInteger inFlight = new AtomicInteger();
  AtomicInteger maxInFlight = new AtomicInteger();

  RuntimeSettings settings = mock(RuntimeSettings.class);
  DeviceRepository devices = mock(DeviceRepository.class);
  FcmPushSender sender;
  String base;

  @BeforeEach
  void up() throws Exception {
    if (keys == null) {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      keys = g.generateKeyPair();
    }
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    server.createContext("/token", x -> {
      tokenCalls.incrementAndGet();
      lastTokenRequest = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      reply(x, tokenStatus, tokenBody);
    });
    server.createContext("/v1/projects/demo-proj/messages:send", x -> {
      int now = inFlight.incrementAndGet();
      maxInFlight.accumulateAndGet(now, Math::max);
      try {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        sendCalls.add(new String[] {x.getRequestHeaders().getFirst("Authorization"), body});
        String token = JSON.readTree(body).path("message").path("token").asString("");
        Object[] ans = sendAnswers.getOrDefault(token, new Object[] {200, "{\"name\":\"projects/demo-proj/messages/1\"}"});
        Thread.sleep(30);
        reply(x, (Integer) ans[0], (String) ans[1]);
      } catch (InterruptedException e) {
        throw new IOException(e);
      } finally {
        inFlight.decrementAndGet();
      }
    });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
    when(settings.get(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(account(base + "/token"));
    sender = new FcmPushSender(settings, devices, JSON, clock, base + "/");
  }

  @AfterEach
  void down() { server.stop(0); }

  static void reply(HttpExchange x, int status, String body) throws IOException {
    byte[] b = body.getBytes(StandardCharsets.UTF_8);
    x.getResponseHeaders().add("Content-Type", "application/json");
    x.sendResponseHeaders(status, b.length);
    x.getResponseBody().write(b);
    x.close();
  }

  static String account(String tokenUri) {
    String pem = "-----BEGIN PRIVATE KEY-----\n"
        + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("type", "service_account");
    m.put("project_id", "demo-proj");
    m.put("client_email", "bot@demo-proj.iam.gserviceaccount.com");
    m.put("private_key", pem);
    if (tokenUri != null) m.put("token_uri", tokenUri);
    return JSON.writeValueAsString(m);
  }

  // ---------- token ----------
  @Test
  void tokenIsFetchedOnceAndCached_andJwtIsCorrectlySigned() throws Exception {
    sender.send(List.of("t1"), "Hi", "There");
    sender.send(List.of("t2", "t3"), "Hi", "There");

    assertThat(tokenCalls.get()).isEqualTo(1);
    assertThat(sendCalls).hasSize(3).allSatisfy(c -> assertThat(c[0]).isEqualTo("Bearer AT-1"));

    // form body: grant_type + assertion, the assertion is an RS256 JWT verifiable with the public key
    String[] pairs = lastTokenRequest.split("&");
    assertThat(pairs[0]).isEqualTo("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer");
    String jwt = java.net.URLDecoder.decode(pairs[1].substring("assertion=".length()), StandardCharsets.UTF_8);
    String[] parts = jwt.split("\\.");
    assertThat(parts).hasSize(3);
    JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    assertThat(header.path("alg").asString()).isEqualTo("RS256");
    assertThat(claims.path("iss").asString()).isEqualTo("bot@demo-proj.iam.gserviceaccount.com");
    assertThat(claims.path("scope").asString()).isEqualTo("https://www.googleapis.com/auth/firebase.messaging");
    assertThat(claims.path("aud").asString()).isEqualTo(base + "/token");
    assertThat(claims.path("iat").asLong()).isEqualTo(clock.now.getEpochSecond());
    assertThat(claims.path("exp").asLong()).isEqualTo(clock.now.getEpochSecond() + 3600);
    Signature v = Signature.getInstance("SHA256withRSA");
    v.initVerify(keys.getPublic());
    v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(v.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  @Test
  void tokenIsRefreshedFiveMinutesBeforeExpiry() {
    sender.send(List.of("t1"), "a", "b");
    clock.now = clock.now.plusSeconds(3600 - 301); // still inside the safe window
    sender.send(List.of("t1"), "a", "b");
    assertThat(tokenCalls.get()).isEqualTo(1);

    tokenBody = "{\"access_token\":\"AT-2\",\"expires_in\":3600}";
    clock.now = clock.now.plusSeconds(2); // now within 5 minutes of expiry
    sender.send(List.of("t1"), "a", "b");
    assertThat(tokenCalls.get()).isEqualTo(2);
    assertThat(sendCalls.get(2)[0]).isEqualTo("Bearer AT-2");
  }

  @Test
  void tokenIsDiscardedWhenTheSettingChanges() {
    sender.send(List.of("t1"), "a", "b");
    tokenBody = "{\"access_token\":\"AT-9\"}"; // no expires_in: defaults to an hour
    when(settings.get(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(account(base + "/token").replace("demo-proj.iam", "other.iam"));
    sender.send(List.of("t1"), "a", "b");
    assertThat(tokenCalls.get()).isEqualTo(2);
    assertThat(sendCalls.get(1)[0]).isEqualTo("Bearer AT-9");
  }

  @Test
  void anExpiredTokenAnswer401DropsTheCachedToken() {
    sendAnswers.put("bad", new Object[] {401, "{\"error\":{\"status\":\"UNAUTHENTICATED\"}}"});
    sender.send(List.of("bad"), "a", "b");
    sender.send(List.of("ok"), "a", "b");
    assertThat(tokenCalls.get()).isEqualTo(2);
    verify(devices, never()).deleteToken(anyString());
  }

  // ---------- message shape ----------
  @Test
  void requestBodyHasTheFcmV1Shape() {
    sender.send(List.of("device-1"), "Order packed", "Your order is ready");
    JsonNode m = parse(sendCalls.get(0)[1]).path("message");
    assertThat(m.path("token").asString()).isEqualTo("device-1");
    assertThat(m.path("notification").path("title").asString()).isEqualTo("Order packed");
    assertThat(m.path("notification").path("body").asString()).isEqualTo("Your order is ready");
    assertThat(m.path("android").path("priority").asString()).isEqualTo("HIGH");
    assertThat(parse(sendCalls.get(0)[1]).has("validate_only")).isFalse();
  }

  @Test
  void longTextIsTruncated() {
    sender.send(List.of("d"), "T".repeat(150), "B".repeat(500));
    JsonNode n = parse(sendCalls.get(0)[1]).path("message").path("notification");
    assertThat(n.path("title").asString()).hasSize(100);
    assertThat(n.path("body").asString()).hasSize(400);
  }

  @Test
  void nullTextBecomesEmptyAndShortTextIsKept() {
    sender.send(List.of("d"), null, "short");
    JsonNode n = parse(sendCalls.get(0)[1]).path("message").path("notification");
    assertThat(n.path("title").asString()).isEmpty();
    assertThat(n.path("body").asString()).isEqualTo("short");
  }

  @Test
  void emptyOrNullTokenListDoesNothing() {
    sender.send(List.of(), "a", "b");
    sender.send(null, "a", "b");
    assertThat(tokenCalls.get()).isZero();
    assertThat(sendCalls).isEmpty();
  }

  @Test
  void sendsInParallelButNeverMoreThanEightAtOnce() {
    List<String> tokens = IntStream.range(0, 40).mapToObj(i -> "tok" + i).toList();
    sender.send(tokens, "a", "b");
    assertThat(sendCalls).hasSize(40);
    assertThat(maxInFlight.get()).isBetween(2, 8);
  }

  // ---------- dead tokens and other errors ----------
  @Test
  void deadTokensAreDeleted() {
    sendAnswers.put("gone404", new Object[] {404, "{\"error\":{\"status\":\"NOT_FOUND\"}}"});
    sendAnswers.put("gone-unreg", new Object[] {400, "{\"error\":{\"status\":\"UNREGISTERED\"}}"});
    sendAnswers.put("gone-invalid", new Object[] {400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}"});
    sendAnswers.put("gone-nobody", new Object[] {404, "not json"});
    sender.send(List.of("gone404", "gone-unreg", "gone-invalid", "gone-nobody", "alive"), "a", "b");
    verify(devices).deleteToken("gone404");
    verify(devices).deleteToken("gone-unreg");
    verify(devices).deleteToken("gone-invalid");
    verify(devices).deleteToken("gone-nobody");
    verify(devices, never()).deleteToken("alive");
  }

  @Test
  void otherFailuresAreSwallowedAndTheRestIsStillSent() {
    sendAnswers.put("boom", new Object[] {500, "{\"error\":{\"status\":\"INTERNAL\"}}"});
    sendAnswers.put("quota", new Object[] {429, "<html>busy</html>"});
    sender.send(List.of("boom", "quota", "fine"), "a", "b");
    assertThat(sendCalls).hasSize(3);
    verify(devices, never()).deleteToken(anyString());
  }

  @Test
  void aFailingTokenDeleteDoesNotEscape() {
    sendAnswers.put("gone", new Object[] {404, "{}"});
    org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(devices).deleteToken("gone");
    sender.send(List.of("gone", "next"), "a", "b");
    assertThat(sendCalls).hasSize(2);
  }

  @Test
  void unreachableFirebaseIsSwallowed() {
    server.stop(0);
    sender.send(List.of("d"), "a", "b"); // token endpoint unreachable too
    assertThat(sendCalls).isEmpty();
  }

  @Test
  void unreachableSendEndpointIsSwallowed() {
    sender.send(List.of("warm"), "a", "b"); // caches the access token
    FcmPushSender dead = new FcmPushSender(settings, devices, JSON, clock, "http://127.0.0.1:1");
    // fetches a token from the live fake server, then fails to reach FCM itself
    dead.send(List.of("d"), "a", "b");
    verify(devices, never()).deleteToken(anyString());
  }

  @Test
  void tokenEndpointRefusalIsSwallowed() {
    tokenStatus = 400;
    tokenBody = "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT\"}";
    sender.send(List.of("d"), "a", "b");
    assertThat(sendCalls).isEmpty();
    tokenBody = "<html>nope</html>";
    sender.send(List.of("d"), "a", "b");
    tokenBody = "{\"error\":\"\"}";
    sender.send(List.of("d"), "a", "b");
    assertThat(sendCalls).isEmpty();
  }

  @Test
  void tokenAnswerWithoutAccessTokenOrNotJsonIsSwallowed() {
    tokenBody = "{\"expires_in\":10}";
    sender.send(List.of("d"), "a", "b");
    tokenBody = "garbage";
    sender.send(List.of("d"), "a", "b");
    assertThat(sendCalls).isEmpty();
  }

  @Test
  void missingOrMalformedServiceAccountIsSwallowed() {
    for (String bad : new String[] {"", "   ", "not json", "{}", "{\"project_id\":\"p\",\"client_email\":\"e\",\"private_key\":\"zzz\"}",
        "{\"project_id\":\"p\",\"client_email\":\"e\",\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\"}"}) {
      when(settings.get(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(bad);
      sender.send(List.of("d"), "a", "b");
    }
    assertThat(sendCalls).isEmpty();
    assertThat(tokenCalls.get()).isZero();
  }

  @Test
  void interruptedThreadIsReportedAsFailureWithoutThrowing() {
    Thread.currentThread().interrupt();
    try {
      ConnectionTester.Result r = sender.validate();
      assertThat(r.ok()).isFalse();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  // ---------- validate (used by the tester) ----------
  @Test
  void validateSendsValidateOnlyAndAccepts400ForTheFakeDeviceToken() {
    sendAnswers.put("validate-only-check-token", new Object[] {400, "{\"error\":{\"status\":\"INVALID_ARGUMENT\"}}"});
    ConnectionTester.Result r = sender.validate();
    assertThat(r.ok()).isTrue();
    assertThat(r.message()).contains("accepted").contains("demo-proj");
    JsonNode body = parse(sendCalls.get(0)[1]);
    assertThat(body.path("validate_only").asBoolean()).isTrue();
    verify(devices, never()).deleteToken(anyString());
  }

  @Test
  void validateAcceptsPlain200And404() {
    assertThat(sender.validate().ok()).isTrue();
    sendAnswers.put("validate-only-check-token", new Object[] {404, "{}"});
    assertThat(sender.validate().ok()).isTrue();
  }

  @Test
  void validateRejectsPermissionProblems() {
    sendAnswers.put("validate-only-check-token", new Object[] {403, "{}"});
    ConnectionTester.Result r = sender.validate();
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).contains("403").contains("demo-proj");
    sendAnswers.put("validate-only-check-token", new Object[] {401, "{}"});
    assertThat(sender.validate().ok()).isFalse();
    assertThat(tokenCalls.get()).isEqualTo(1); // the 401 dropped the cached token ...
    sender.validate();
    assertThat(tokenCalls.get()).isEqualTo(2); // ... so the next check signs in again
  }

  @Test
  void validateReportsSignInRefusalAndBadAccounts() {
    tokenStatus = 401;
    tokenBody = "{\"error\":\"invalid_grant\"}";
    ConnectionTester.Result r = sender.validate();
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).startsWith("Firebase refused it:").contains("401");
    when(settings.get(Settings.FCM_SERVICE_ACCOUNT)).thenReturn("");
    assertThat(sender.validate().message()).contains("no Firebase service account");
  }

  @Test
  void neverLeaksTheKeyOrDeviceTokenInMessages() {
    when(settings.get(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(account(base + "/token").replace("MII", "!!"));
    ConnectionTester.Result r = sender.validate();
    assertThat(r.ok()).isFalse();
    assertThat(r.message()).doesNotContain("BEGIN").doesNotContain("MII");
  }

  static JsonNode parse(String s) { return JSON.readTree(s); }

  @Test
  void aKeyThatCannotSignIsSwallowedAndNeverReachesTheTokenEndpoint() throws Exception {
    // Same RSA key but with a damaged CRT parameter: it is readable, yet every signature attempt fails its own check.
    var good = (java.security.interfaces.RSAPrivateCrtKey) keys.getPrivate();
    var damaged = new java.security.spec.RSAPrivateCrtKeySpec(good.getModulus(), good.getPublicExponent(), good.getPrivateExponent(),
        good.getPrimeP(), good.getPrimeQ(), good.getPrimeExponentP().add(java.math.BigInteger.TWO), good.getPrimeExponentQ(), good.getCrtCoefficient());
    byte[] der = java.security.KeyFactory.getInstance("RSA").generatePrivate(damaged).getEncoded();
    Map<String, Object> m = new java.util.LinkedHashMap<>();
    m.put("project_id", "demo-proj");
    m.put("client_email", "bot@demo-proj.iam.gserviceaccount.com");
    m.put("private_key", "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(der) + "\n-----END PRIVATE KEY-----\n");
    m.put("token_uri", base + "/token");
    when(settings.get(Settings.FCM_SERVICE_ACCOUNT)).thenReturn(JSON.writeValueAsString(m));

    sender.send(List.of("d"), "a", "b");

    assertThat(sendCalls).isEmpty();
    assertThat(tokenCalls.get()).isZero();
    assertThat(sender.validate().ok()).isFalse();
  }
}
