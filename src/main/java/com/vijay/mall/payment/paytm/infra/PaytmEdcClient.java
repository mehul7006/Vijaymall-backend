package com.vijay.mall.payment.paytm.infra;

import com.vijay.mall.payment.paytm.app.PaytmSettings;
import com.vijay.mall.payment.paytm.domain.PaytmReply;
import com.vijay.mall.payment.paytm.domain.PaytmReply.TxnDetails;
import com.vijay.mall.payment.paytm.domain.PaytmResultCode;
import com.vijay.mall.payment.paytm.domain.PaytmResultCode.Outcome;
import com.vijay.mall.payment.paytm.domain.PaytmTerminalService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to Paytm's wireless (cloud ECR) APIs over plain HTTP: sale {@code /ecr/payment/request}, status {@code /ecr/V2/payment/status}
 * and void {@code /ecr/void}. Connect time-out 3 s, read time-out 10 s. A sale is sent exactly once (a retry could charge the
 * customer twice; a lost answer is resolved with a status call); only status calls are retried, and only when the network failed.
 *
 * <p>Nothing here has run against a real Paytm server yet: every field name and the request {@code version} follow Paytm's pages
 * as read and are listed in the go-live checklist.
 */
@Component
public class PaytmEdcClient implements PaytmTerminalService {
  private static final Logger log = LoggerFactory.getLogger(PaytmEdcClient.class);
  /** Value of {@code head.version}. UNCONFIRMED with Paytm. */
  static final String VERSION = "3.1";
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  /** Format of every Paytm date and time. */
  public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final int STATUS_ATTEMPTS = 3;
  private static final int MAX_TEXT = 200;

  private final PaytmSettings cfg;
  private final JsonMapper json;
  private final Clock clock;
  private final long backoffMillis;
  private final SimpleClientHttpRequestFactory factory;

  /** Creates the client with the standard time-outs and a 300 ms pause between status retries. */
  @Autowired
  public PaytmEdcClient(PaytmSettings cfg, JsonMapper json, Clock clock) { this(cfg, json, clock, 300); }

  PaytmEdcClient(PaytmSettings cfg, JsonMapper json, Clock clock, long backoffMillis) {
    this.cfg = cfg;
    this.json = json;
    this.clock = clock;
    this.backoffMillis = backoffMillis;
    this.factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(10));
  }

  // ---------- the port ----------
  @Override
  public PaytmReply requestSale(Sale s) {
    Map<String, Object> body = base(s.terminalId(), s.merchantTransactionId(), s.transactionDateTime());
    body.put("transactionAmount", Long.toString(s.amountPaise()));
    body.put("merchantReferenceNo", clean(s.reference(), 50));
    body.put("displayInfo", clean(s.reference(), 20));
    Map<String, Object> ext = new LinkedHashMap<>();
    ext.put("autoAccept", Boolean.toString(cfg.autoAccept()));
    ext.put("paymentMode", cfg.paymentMode());
    body.put("merchantExtendedInfo", ext);
    if (cfg.timeoutConfig() > 0) body.put("timeoutConfig", cfg.timeoutConfig());
    return parse(post("/ecr/payment/request", body), false);
  }

  @Override
  public PaytmReply status(Txn t) {
    PaytmReply reply = null;
    for (int attempt = 1; attempt <= STATUS_ATTEMPTS; attempt++) {
      reply = parse(post("/ecr/V2/payment/status", base(t.terminalId(), t.merchantTransactionId(), t.transactionDateTime())), true);
      if (reply.outcome() != Outcome.NETWORK_ERROR || attempt == STATUS_ATTEMPTS) break;
      pause();
    }
    return reply;
  }

  @Override
  public CancelResult cancelPending(Txn t) {
    PaytmReply reply = status(t);
    return new CancelResult(reply.outcome() == Outcome.FAILED, reply);
  }

  @Override
  public PaytmReply voidSale(Txn t) {
    return parse(post("/ecr/void", base(t.terminalId(), t.merchantTransactionId(), t.transactionDateTime())), false);
  }

  @Override
  public ConnectionResult testConnection(String terminalId) {
    if (!cfg.missing().isEmpty()) {
      return new ConnectionResult(false, "NOT_CONFIGURED", "Save the " + String.join(", ", cfg.missing()) + " first.");
    }
    String probe = "VMTEST" + String.format("%020d", Math.abs(clock.millis()));
    PaytmReply r = status(new Txn(terminalId, probe, TIME.format(clock.instant().atZone(INDIA))));
    String internal = r.internalCode();
    if (r.outcome() == Outcome.NETWORK_ERROR) return new ConnectionResult(false, "NETWORK", r.message());
    if ("BAD_CHECKSUM".equals(internal) || "PAYTM_HTTP_401".equals(internal) || "PAYTM_HTTP_403".equals(internal)) {
      return new ConnectionResult(false, "BAD_CREDENTIALS", "Paytm refused the credentials: check the merchant id, merchant key and channel id.");
    }
    if ("TERMINAL_INACTIVE".equals(internal)) return new ConnectionResult(false, "TERMINAL_INACTIVE", r.message());
    if (r.outcome() == Outcome.ERROR) return new ConnectionResult(false, internal, r.message());
    if (r.outcome() == Outcome.NOT_FOUND) {
      return new ConnectionResult(true, "OK", "Paytm accepted the signature and knows terminal " + terminalId + " (it has no payment with the test id, as expected).");
    }
    return new ConnectionResult(true, "OK", "Paytm accepted the signature for terminal " + terminalId + ".");
  }

  // ---------- building and sending ----------
  private void pause() {
    try {
      Thread.sleep(backoffMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Map<String, Object> base(String tid, String txnId, String dateTime) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("paytmMid", cfg.mid());
    body.put("paytmTid", tid);
    body.put("transactionDateTime", dateTime);
    body.put("merchantTransactionId", txnId);
    return body;
  }

  /** Letters, digits and a few separators only, cut to length: nothing the customer typed reaches Paytm unchecked. */
  static String clean(String s, int max) {
    String c = s == null ? "" : s.replaceAll("[^A-Za-z0-9 _/.-]", "").strip();
    return c.length() > max ? c.substring(0, max) : c;
  }

  /**
   * The ONE place that decides what is signed and sent: the {@code body} object serialized compactly, and exactly those characters
   * are signed and placed in the envelope, so what Paytm verifies is what we signed. Change here if Paytm's sandbox wants another
   * form.
   */
  String envelope(Map<String, Object> body) {
    String bodyJson = json.writeValueAsString(body);
    String checksum = PaytmChecksum.generateSignature(bodyJson, cfg.merchantKey());
    Map<String, Object> head = new LinkedHashMap<>();
    head.put("requestTimeStamp", TIME.format(clock.instant().atZone(INDIA)));
    head.put("channelId", cfg.channelId());
    head.put("checksum", checksum);
    head.put("version", VERSION);
    return "{\"head\":" + json.writeValueAsString(head) + ",\"body\":" + bodyJson + "}";
  }

  private record Raw(int status, String text) {}

  private Optional<Raw> post(String path, Map<String, Object> body) {
    try {
      String payload = envelope(body);
      Raw raw = RestClient.builder().requestFactory(factory).build().post().uri(cfg.baseUrl() + path)
          .contentType(MediaType.APPLICATION_JSON).body(payload)
          .exchange((req, res) -> new Raw(res.getStatusCode().value(), new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
      return Optional.ofNullable(raw);
    } catch (RuntimeException e) {
      log.warn("Paytm call {} got no answer: {}", path, e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  // ---------- reading the answer ----------
  private PaytmReply parse(Optional<Raw> raw, boolean withDetails) {
    if (raw.isEmpty()) {
      return new PaytmReply(Outcome.NETWORK_ERROR, "", "NETWORK", "Could not reach Paytm (no answer in time). The result is not known yet.", null);
    }
    Raw r = raw.get();
    JsonNode body;
    try {
      body = json.readTree(r.text()).path("body");
    } catch (RuntimeException e) {
      body = null;
    }
    JsonNode info = body == null ? null : body.path("resultInfo");
    String code = info == null ? "" : info.path("resultCode").asString("");
    if (code.isEmpty()) {
      if (r.status() >= 500) return new PaytmReply(Outcome.NETWORK_ERROR, "", "NETWORK", "Paytm had a problem (HTTP " + r.status() + "). The result is not known yet.", null);
      return new PaytmReply(Outcome.ERROR, "", "PAYTM_HTTP_" + r.status(), "Paytm answered in a way we could not read (HTTP " + r.status() + ").", null);
    }
    Optional<PaytmResultCode> known = PaytmResultCode.of(code);
    TxnDetails details = withDetails ? details(body) : null;
    if (known.isPresent()) {
      PaytmResultCode k = known.get();
      return new PaytmReply(k.outcome, code, k.internalCode, k.message, details);
    }
    String text = clean(info.path("resultMsg").asString(""), MAX_TEXT);
    return new PaytmReply(Outcome.ERROR, code, "PAYTM_ERROR", "Paytm answered with code " + code + (text.isEmpty() ? "." : ": " + text), details);
  }

  private static String text(JsonNode n, String... names) {
    for (String name : names) {
      String v = n.path(name).asString("");
      if (!v.isBlank()) return v.strip();
    }
    return null;
  }

  private TxnDetails details(JsonNode b) {
    String amount = text(b, "transactionAmount");
    Long paise = null;
    if (amount != null && amount.matches("\\d{1,15}")) paise = Long.parseLong(amount);
    String raw = b.toString();
    return new TxnDetails(text(b, "payMethod"), text(b, "cardScheme"), text(b, "issuerMaskCardNo"), text(b, "retrievalReferenceNo"),
        text(b, "authCode"), text(b, "issuingBankName"), text(b, "acquiringBank"), text(b, "payerVpa", "vpa", "upiVpa"),
        text(b, "bankResponseCode"), text(b, "bankResponseMessage"), paise, raw.length() > 4000 ? raw.substring(0, 4000) : raw);
  }
}
