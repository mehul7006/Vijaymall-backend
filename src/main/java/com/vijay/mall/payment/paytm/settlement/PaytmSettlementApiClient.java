package com.vijay.mall.payment.paytm.settlement;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.paytm.app.PaytmSettings;
import com.vijay.mall.payment.paytm.infra.PaytmChecksum;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads settlements from Paytm's merchant-settlement APIs ({@code Settlement Detail}, {@code Transaction Detail}, {@code Order
 * Detail}, {@code Settlement Summary}) and normalizes them to {@link PaytmSettlementRecord}.
 *
 * <p>UNTESTED against Paytm. Switched off by the setting {@code paytm.settlement.enabled}. The request field names, the paths other
 * than {@code /merchant-settlement/SettlementDetail}, the response shape and the sign-in style (BEARER token, or CHECKSUM with the
 * merchant key placed in {@code head.signature}) are all UNCONFIRMED: the response reader accepts several list names and field
 * spellings and keeps every field it does not map in {@code detail}.
 */
@Component
public class PaytmSettlementApiClient {
  /** The four settlement APIs. */
  public enum Api {
    /** One line per settled transaction; the main one. */ DETAIL("/merchant-settlement/SettlementDetail", "API_DETAIL"),
    /** One line per transaction. UNCONFIRMED path. */ TRANSACTION("/merchant-settlement/TransactionDetail", "API_TRANSACTION"),
    /** One line per order. UNCONFIRMED path. */ ORDER("/merchant-settlement/OrderDetail", "API_ORDER"),
    /** One line per payout, with the bank account. UNCONFIRMED path. */ SUMMARY("/merchant-settlement/SettlementSummary", "API_SUMMARY");

    final String path;
    final String source;

    Api(String path, String source) {
      this.path = path;
      this.source = source;
    }
  }

  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
  private static final List<String> LIST_NAMES = List.of("settlementTransactionList", "settlementDetailList", "transactions", "orders", "settlements", "data", "result", "list");

  private final PaytmSettings cfg;
  private final JsonMapper json;
  private final Clock clock;
  private final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

  /** Creates the client (connect time-out 3 s, read time-out 15 s). */
  public PaytmSettlementApiClient(PaytmSettings cfg, JsonMapper json, Clock clock) {
    this.cfg = cfg;
    this.json = json;
    this.clock = clock;
    factory.setConnectTimeout(Duration.ofSeconds(3));
    factory.setReadTimeout(Duration.ofSeconds(15));
  }

  /** Fetches the last {@code paytm.settlement.syncDays} days from the given API. */
  public List<PaytmSettlementRecord> sync(Api api) {
    LocalDate to = clock.instant().atZone(INDIA).toLocalDate();
    return fetch(api, to.minusDays(cfg.syncDays()), to);
  }

  /**
   * Fetches one API for a date range (inclusive).
   *
   * @throws ApiException 503 {@code PAYTM_SETTLEMENT_DISABLED} when the setting is off, 502 {@code PAYTM_SETTLEMENT_ERROR} when
   *     Paytm cannot be reached or answers something unreadable
   */
  public List<PaytmSettlementRecord> fetch(Api api, LocalDate from, LocalDate to) {
    if (!cfg.settlementEnabled()) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYTM_SETTLEMENT_DISABLED", "Reading settlements from Paytm is switched off in the configuration");
    }
    if (cfg.mid().isEmpty()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYTM_NOT_CONFIGURED", "The Paytm merchant id is not saved");
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("mid", cfg.mid());
    body.put("startDate", from.toString());
    body.put("endDate", to.toString());
    String text;
    try {
      var spec = RestClient.builder().requestFactory(factory).build().post().uri(cfg.settlementBaseUrl() + api.path).contentType(MediaType.APPLICATION_JSON);
      String payload;
      if ("CHECKSUM".equals(cfg.settlementAuth())) {
        String bodyJson = json.writeValueAsString(body);
        payload = "{\"head\":{\"tokenType\":\"CHECKSUM\",\"signature\":\"" + PaytmChecksum.generateSignature(bodyJson, cfg.merchantKey())
            + "\"},\"body\":" + bodyJson + "}";
      } else {
        payload = json.writeValueAsString(body);
        spec = spec.header("Authorization", "Bearer " + cfg.settlementToken());
      }
      text = spec.body(payload).exchange((req, res) -> {
        if (res.getStatusCode().isError()) throw new IllegalStateException("HTTP " + res.getStatusCode().value());
        return new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
      });
    } catch (RuntimeException e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYTM_SETTLEMENT_ERROR", "Could not read settlements from Paytm (" + e.getClass().getSimpleName() + ")");
    }
    try {
      return parse(api, text);
    } catch (RuntimeException e) {
      throw new ApiException(HttpStatus.BAD_GATEWAY, "PAYTM_SETTLEMENT_ERROR", "Paytm's settlement answer could not be read");
    }
  }

  /** Reads an API answer: the list under any of the known names (or the root array, or under {@code body}) becomes records. */
  List<PaytmSettlementRecord> parse(Api api, String text) {
    JsonNode root = json.readTree(text);
    JsonNode scope = root.has("body") ? root.path("body") : root;
    JsonNode list = scope.isArray() ? scope : null;
    for (String n : LIST_NAMES) {
      if (list == null && scope.path(n).isArray()) list = scope.path(n);
    }
    if (list == null) throw new IllegalArgumentException("no list in the answer");
    List<PaytmSettlementRecord> out = new ArrayList<>();
    for (JsonNode n : list) out.add(record(api, n));
    return out;
  }

  private static final List<String> MAPPED = List.of("transactionId", "txnId", "transId", "orderId", "transactionType", "txnType", "status", "paymentMode",
      "requestType", "rrnCode", "rrn", "maskedCardNo", "cardNetwork", "payerVpa", "vpa", "posId", "extSerialNo", "amount", "settledAmount", "commission",
      "gst", "payoutId", "utrNo", "utr", "transactionDate", "txnDate", "settledDate", "payoutDate", "splitFlag", "bankName", "accountNumber", "bankAccount");

  private static String s(JsonNode n, String... names) {
    for (String name : names) {
      JsonNode v = n.path(name);
      if (!v.isMissingNode() && !v.isNull() && !v.asString("").isBlank()) return v.asString("").strip();
    }
    return null;
  }

  private PaytmSettlementRecord record(Api api, JsonNode n) {
    Map<String, String> detail = new LinkedHashMap<>();
    n.properties().forEach(e -> {
      if (!MAPPED.contains(e.getKey()) && !e.getValue().isNull()) detail.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asString("") : e.getValue().toString());
    });
    String status = s(n, "status");
    LocalDateTime txnDate = PaytmValues.dateTime(s(n, "transactionDate", "txnDate"));
    String account = s(n, "accountNumber", "bankAccount");
    return new PaytmSettlementRecord("paytm", api.source, s(n, "transactionId", "txnId", "transId"), s(n, "orderId"),
        PaytmValues.type(s(n, "transactionType", "txnType")), status == null ? null : status.toUpperCase(), PaytmValues.mode(s(n, "paymentMode")),
        s(n, "requestType"), s(n, "rrnCode", "rrn"), s(n, "maskedCardNo"), s(n, "cardNetwork"), PaytmValues.maskVpa(s(n, "payerVpa", "vpa")),
        s(n, "posId"), s(n, "extSerialNo"), PaytmValues.paise(s(n, "amount")), PaytmValues.paise(s(n, "settledAmount")),
        PaytmValues.paise(s(n, "commission")), PaytmValues.paise(s(n, "gst")), s(n, "payoutId"), s(n, "utrNo", "utr"), txnDate,
        PaytmValues.date(s(n, "settledDate", "payoutDate")), s(n, "splitFlag"), s(n, "bankName"),
        account == null ? null : "****" + account.substring(Math.max(0, account.length() - 4)), detail);
  }
}
