package com.vijay.mall.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.Settings;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class HttpSmsSenderTest {
  record Captured(String method, String uri, String body, Headers headers) {}

  private HttpServer server;
  private final List<Captured> seen = new CopyOnWriteArrayList<>();
  private volatile int respond = 200;
  private final FakeSettings fs = new FakeSettings();
  private HttpSmsSender sender;
  private ListAppender<ILoggingEvent> logs;
  private Logger logger;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", ex -> {
      seen.add(new Captured(ex.getRequestMethod(), ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()),
          new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders()));
      ex.sendResponseHeaders(respond, -1);
      ex.close();
    });
    server.start();
    fs.with(Settings.SMS_CHANNEL, "http").with(Settings.SMS_HTTP_URL, base() + "/send").with(Settings.SMS_SENDER_ID, "VIJAYM")
        .with(Settings.SMS_DLT_TEMPLATE_ID, "T123").with(Settings.SMS_HTTP_HEADERS, "{\"authkey\":\"SECRETKEY\",\"X-Num\":5}");
    sender = new HttpSmsSender(fs.settings, JsonMapper.builder().build());
    logger = (Logger) LoggerFactory.getLogger(HttpSmsSender.class);
    logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void stop() {
    logger.detachAppender(logs);
    server.stop(0);
  }

  private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

  private String allLogs() { return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b); }

  @Test
  void postSubstitutesPlaceholdersAndSendsHeaders() {
    sender.send("9876543210", "424242");
    Captured c = seen.getFirst();
    assertThat(c.method()).isEqualTo("POST");
    assertThat(c.uri()).isEqualTo("/send");
    assertThat(c.headers().getFirst("Content-Type")).isEqualTo("application/json");
    assertThat(c.headers().getFirst("authkey")).isEqualTo("SECRETKEY");
    assertThat(c.headers().getFirst("X-Num")).isEqualTo("5");
    assertThat(c.body()).isEqualTo("{\"mobile\":\"919876543210\",\"message\":\"Your Vijay Super Mall login code is 424242. It is valid for 5 minutes. "
        + "Do not share it with anyone.\",\"sender\":\"VIJAYM\",\"template_id\":\"T123\"}");
    assertThat(allLogs()).contains("SMS sent to 98******10 status=200").doesNotContain("424242").doesNotContain("SECRETKEY");
  }

  @Test
  void valuesInTheJsonBodyAreEscaped() {
    fs.with(Settings.SMS_MESSAGE, "Hi \"you\"\nline2 \\ code {otp}");
    sender.send("9876543210", "111222");
    var tree = JsonMapper.builder().build().readTree(seen.getFirst().body());
    assertThat(tree.get("message").asString()).isEqualTo("Hi \"you\"\nline2 \\ code 111222");
  }

  @Test
  void getEncodesValuesInTheUrlAndSendsNoBody() {
    fs.with(Settings.SMS_HTTP_METHOD, "GET").with(Settings.SMS_HTTP_URL, base() + "/api?to={mobile}&text={message}&s={senderId}&t={dltTemplateId}&c={otp}")
        .with(Settings.SMS_MESSAGE, "a&b=c d {otp}").with(Settings.SMS_HTTP_HEADERS, "{\"content-type\":\"text/plain\"}");
    sender.send("9876543210", "777888");
    Captured c = seen.getFirst();
    assertThat(c.method()).isEqualTo("GET");
    assertThat(c.uri()).isEqualTo("/api?to=9876543210&text=a%26b%3Dc+d+777888&s=VIJAYM&t=T123&c=777888");
    assertThat(c.body()).isEmpty();
    assertThat(c.headers().getFirst("Content-Type")).isEqualTo("text/plain");
  }

  @Test
  void postKeepsAContentTypeTheAdminSet() {
    fs.with(Settings.SMS_HTTP_HEADERS, "{\"Content-Type\":\"application/json; charset=utf-8\"}");
    sender.send("9876543210", "1");
    assertThat(seen.getFirst().headers().get("Content-Type")).containsExactly("application/json; charset=utf-8");
  }

  @Test
  void noHeadersConfiguredIsFine() {
    fs.with(Settings.SMS_HTTP_HEADERS, "");
    sender.send("9876543210", "1");
    assertThat(seen).hasSize(1);
  }

  @Test
  void non2xxIsAProviderError() {
    respond = 500;
    assertThatThrownBy(() -> sender.send("9876543210", "909090")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
      assertThat(e.code()).isEqualTo("SMS_PROVIDER_ERROR");
      assertThat(e.getMessage()).doesNotContain("909090").doesNotContain("SECRETKEY").doesNotContain("127.0.0.1");
    });
    assertThat(allLogs()).contains("status=500").doesNotContain("909090").doesNotContain("SECRETKEY").doesNotContain("9876543210");
  }

  @Test
  void status300And199BoundariesAreErrors() {
    respond = 301;
    assertThatThrownBy(() -> sender.send("9876543210", "1")).isInstanceOf(ApiException.class);
    respond = 204;
    sender.send("9876543210", "1");
  }

  @Test
  void connectionRefusedIsAProviderError() {
    int port = server.getAddress().getPort();
    server.stop(0);
    fs.with(Settings.SMS_HTTP_URL, "http://127.0.0.1:" + port + "/send?key=topsecret");
    assertThatThrownBy(() -> sender.send("9876543210", "1")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("SMS_PROVIDER_ERROR");
      assertThat(e.getMessage()).doesNotContain("topsecret");
    });
    assertThat(allLogs()).doesNotContain("topsecret");
  }

  @Test
  void interruptedSendIsAProviderErrorAndKeepsTheFlag() {
    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> sender.send("9876543210", "1")).isInstanceOfSatisfying(ApiException.class,
          e -> assertThat(e.code()).isEqualTo("SMS_PROVIDER_ERROR"));
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void unusableSettingsAreAProviderError() {
    for (String badHeaders : List.of("[1,2]", "{\"Host\":\"x\"}", "{\"bad name\":\"x\"}", "not json")) {
      fs.with(Settings.SMS_HTTP_HEADERS, badHeaders);
      assertThatThrownBy(() -> sender.send("9876543210", "1")).isInstanceOfSatisfying(ApiException.class,
          e -> assertThat(e.code()).isEqualTo("SMS_PROVIDER_ERROR"));
    }
    fs.with(Settings.SMS_HTTP_HEADERS, "").with(Settings.SMS_HTTP_URL, "http://bad host/");
    assertThatThrownBy(() -> sender.send("9876543210", "1")).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("SMS_PROVIDER_ERROR"));
  }

  @Test
  void blankUrlIsNotConfigured() {
    fs.with(Settings.SMS_HTTP_URL, " ");
    assertThatThrownBy(() -> sender.send("9876543210", "1")).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(e.code()).isEqualTo("SMS_NOT_CONFIGURED");
    });
  }

  @Test
  void maskHidesTheMiddle() {
    assertThat(HttpSmsSender.mask("9876543210")).isEqualTo("98******10");
    assertThat(HttpSmsSender.mask("1234")).isEqualTo("****");
    assertThat(HttpSmsSender.mask(null)).isEqualTo("****");
  }
}
