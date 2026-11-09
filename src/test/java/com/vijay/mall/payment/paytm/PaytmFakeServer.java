package com.vijay.mall.payment.paytm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vijay.mall.payment.paytm.infra.PaytmChecksum;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An in-JVM stand-in for Paytm's wireless machine APIs (sale, status, void), answering from scripted scenarios. It checks the
 * request checksum for real with the merchant key, so a wrong key is answered with 0330 like Paytm would.
 */
public final class PaytmFakeServer implements AutoCloseable {
  /** Merchant key the fake expects (16 characters). */
  public static final String KEY = "0123456789abcdef";
  /** The scenarios a sale can follow. */
  public enum Script {
    /** Accepted, pending once, then paid by card. */ CARD_SUCCESS,
    /** Accepted, then paid by UPI at once. */ UPI_SUCCESS,
    /** Accepted and pending for ever. */ PENDING_FOREVER,
    /** Rejected: machine inactive. */ TERMINAL_INACTIVE,
    /** Rejected: the machine already has an open request. */ BUSY,
    /** Duplicate transaction id. */ DUPLICATE,
    /** Rejected: bad checksum. */ BAD_CHECKSUM,
    /** The answer is lost (connection dropped) but the sale reached the machine and is later paid by card. */ DROP_THEN_CARD,
    /** The answer is HTTP 500 and the machine never got the sale. */ SERVER_ERROR_NEVER_ARRIVED,
    /** A result code nobody documented. */ UNKNOWN_CODE,
    /** Accepted, pending, then the cashier cancels on the machine (0011). */ CANCELLED_ON_MACHINE,
    /** Accepted, paid, but Paytm reports another amount. */ WRONG_AMOUNT
  }

  private record Txn(Script script, long amountPaise, int[] statusCalls) {}

  private final HttpServer server;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, Txn> txns = new ConcurrentHashMap<>();
  private final Deque<Script> queue = new ArrayDeque<>();
  private volatile Script fallback = Script.CARD_SUCCESS;
  /** Result code the void call answers with. */
  public volatile String voidCode = "0009";
  /** Terminal ids Paytm treats as inactive for status calls. */
  public final List<String> inactiveTerminals = new CopyOnWriteArrayList<>();
  /** Every request received, as {@code path body}. */
  public final List<String> requests = new CopyOnWriteArrayList<>();
  /** Head checksum of the last request. */
  public volatile String lastChecksum;
  /** Head of the last request. */
  public volatile JsonNode lastHead;
  /** Body of the last request. */
  public volatile JsonNode lastBody;

  /** Starts on a free local port. */
  public PaytmFakeServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  /** The base address to configure. */
  public String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

  /** The next sale follows this script (queued in order); sales after the queue is empty follow the default. */
  public synchronized PaytmFakeServer next(Script s) {
    queue.add(s);
    return this;
  }

  /** The script used when nothing is queued. */
  public void fallback(Script s) { fallback = s; }

  /** How many requests hit the path. */
  public long count(String path) { return requests.stream().filter(r -> r.startsWith(path + " ")).count(); }

  @Override public void close() { server.stop(0); }

  private synchronized Script takeScript() { return queue.isEmpty() ? fallback : queue.poll(); }

  private void handle(HttpExchange ex) throws IOException {
    String text = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String path = ex.getRequestURI().getPath();
    requests.add(path + " " + text);
    JsonNode root = json.readTree(text);
    JsonNode body = root.path("body");
    lastHead = root.path("head");
    lastBody = body;
    lastChecksum = lastHead.path("checksum").asString("");
    String rawBody = text.substring(text.indexOf("\"body\":") + 7, text.length() - 1);
    String tx = body.path("merchantTransactionId").asString("");
    String tid = body.path("paytmTid").asString("");
    if (!PaytmChecksum.verifySignature(rawBody, KEY, lastChecksum)) {
      reply(ex, 200, "F", "0330", "Checksum mismatch", Map.of());
      return;
    }
    switch (path) {
      case "/ecr/payment/request" -> sale(ex, body, tx);
      case "/ecr/V2/payment/status" -> status(ex, tx, tid);
      case "/ecr/void" -> {
        boolean ok = "0009".equals(voidCode) || "0000".equals(voidCode);
        reply(ex, 200, ok ? "S" : "F", voidCode, "void", Map.of());
      }
      default -> reply(ex, 404, "F", "9999", "no such path", Map.of());
    }
  }

  private void sale(HttpExchange ex, JsonNode body, String tx) throws IOException {
    Script s = takeScript();
    long amount = Long.parseLong(body.path("transactionAmount").asString("0"));
    switch (s) {
      case TERMINAL_INACTIVE -> reply(ex, 200, "F", "0007", "inactive", Map.of());
      case BUSY -> reply(ex, 200, "F", "0333", "busy", Map.of());
      case DUPLICATE -> reply(ex, 200, "F", "0233", "duplicate", Map.of());
      case BAD_CHECKSUM -> reply(ex, 200, "F", "0330", "bad", Map.of());
      case UNKNOWN_CODE -> reply(ex, 200, "F", "0999", "something odd <b>happened</b>", Map.of());
      case SERVER_ERROR_NEVER_ARRIVED -> {
        ex.sendResponseHeaders(500, -1);
        ex.close();
      }
      case DROP_THEN_CARD -> {
        txns.put(tx, new Txn(Script.CARD_SUCCESS, amount, new int[1]));
        ex.close();
      }
      default -> {
        txns.put(tx, new Txn(s, amount, new int[1]));
        reply(ex, 200, "S", "0009", "accepted", Map.of());
      }
    }
  }

  private void status(HttpExchange ex, String tx, String tid) throws IOException {
    if (inactiveTerminals.contains(tid)) {
      reply(ex, 200, "F", "0007", "inactive", Map.of());
      return;
    }
    Txn t = txns.get(tx);
    if (t == null) {
      reply(ex, 200, "F", "0404", "not found", Map.of());
      return;
    }
    int n = t.statusCalls()[0]++;
    switch (t.script()) {
      case CARD_SUCCESS -> {
        if (n == 0) reply(ex, 200, "S", "0010", "pending", Map.of());
        else reply(ex, 200, "S", "0000", "ok", card(t.amountPaise()));
      }
      case UPI_SUCCESS -> reply(ex, 200, "S", "0000", "ok", upi(t.amountPaise()));
      case CANCELLED_ON_MACHINE -> {
        if (n == 0) reply(ex, 200, "S", "0030", "pending", Map.of());
        else reply(ex, 200, "F", "0011", "cancelled", Map.of());
      }
      case WRONG_AMOUNT -> reply(ex, 200, "S", "0000", "ok", card(t.amountPaise() + 100));
      default -> reply(ex, 200, "S", "0010", "pending", Map.of());
    }
  }

  private static Map<String, String> card(long amount) {
    return Map.ofEntries(Map.entry("payMethod", "CREDIT_CARD"), Map.entry("cardScheme", "VISA"), Map.entry("issuerMaskCardNo", "411111XXXXXX1111"),
        Map.entry("retrievalReferenceNo", "412345678901"), Map.entry("authCode", "A1B2C3"), Map.entry("issuingBankName", "HDFC Bank"),
        Map.entry("acquiringBank", "Paytm Payments Bank"), Map.entry("bankResponseCode", "00"), Map.entry("bankResponseMessage", "Approved"),
        Map.entry("transactionAmount", Long.toString(amount)));
  }

  private static Map<String, String> upi(long amount) {
    return Map.of("payMethod", "UPI", "retrievalReferenceNo", "998877665544", "payerVpa", "customer@okbank", "transactionAmount", Long.toString(amount));
  }

  private void reply(HttpExchange ex, int http, String status, String code, String msg, Map<String, String> extra) throws IOException {
    List<String> parts = new ArrayList<>();
    parts.add("\"resultInfo\":" + json.writeValueAsString(Map.of("resultStatus", status, "resultCode", code, "resultMsg", msg)));
    extra.forEach((k, v) -> parts.add(json.writeValueAsString(k) + ":" + json.writeValueAsString(v)));
    String out = "{\"head\":{\"version\":\"3.1\"},\"body\":{" + String.join(",", parts) + "}}";
    byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json");
    ex.sendResponseHeaders(http, bytes.length);
    ex.getResponseBody().write(bytes);
    ex.close();
  }
}
