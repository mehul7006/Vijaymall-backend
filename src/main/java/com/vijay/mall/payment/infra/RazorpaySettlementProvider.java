package com.vijay.mall.payment.infra;

import com.vijay.mall.payment.domain.SettlementProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * Razorpay's settlement APIs over the same HTTP client and keys as {@link RazorpayGateway}: {@code GET /v1/settlements},
 * {@code GET /v1/settlements/{id}}, {@code GET /v1/settlements/recon/combined} and {@code GET /v1/payments/{id}}.
 *
 * <p>Written from Razorpay's public API reference and tested against canned responses only: it has not run against a live
 * account yet. Fee handling follows the reference (Razorpay's {@code fee} / {@code fees} include GST, {@code tax} is the GST part), so
 * this adapter reports {@code fee = fee - tax} and {@code tax} separately. Check this against the first real settlement.
 */
@Component
public class RazorpaySettlementProvider implements SettlementProvider {
  static final int PAGE_SIZE = 100;
  static final int RECON_PAGE_SIZE = 500;
  static final int MAX_RECON_PAGES = 20;
  static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private final RazorpayGateway gateway;

  /** Creates the adapter on top of the Razorpay gateway (shares its keys, base URL and timeouts). */
  public RazorpaySettlementProvider(RazorpayGateway gateway) { this.gateway = gateway; }

  @Override public String provider() { return "razorpay"; }

  @Override public boolean configured() { return gateway.configured(); }

  @Override
  public Listing listSettlements(Instant from, Instant to, String cursor) {
    int skip = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
    JsonNode res = gateway.client().get().uri("/v1/settlements?from={f}&to={t}&count={c}&skip={s}", from.getEpochSecond(), to.getEpochSecond(),
        PAGE_SIZE, skip).headers(gateway::auth).retrieve().body(JsonNode.class);
    List<Summary> out = new ArrayList<>();
    JsonNode items = res == null ? null : res.path("items");
    if (items != null) items.forEach(n -> out.add(summary(n)));
    return new Listing(out, out.size() < PAGE_SIZE ? null : Integer.toString(skip + PAGE_SIZE));
  }

  @Override
  public Optional<Detail> settlementDetail(String settlementId) {
    JsonNode head;
    try {
      head = gateway.client().get().uri("/v1/settlements/{id}", settlementId).headers(gateway::auth).retrieve().body(JsonNode.class);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    if (head == null || head.path("id").asString(null) == null) return Optional.empty();
    Summary summary = summary(head);
    LocalDate day = summary.settledAt().atZone(INDIA).toLocalDate();
    List<Item> items = new ArrayList<>();
    for (int page = 0; page < MAX_RECON_PAGES; page++) {
      JsonNode res = gateway.client().get().uri("/v1/settlements/recon/combined?year={y}&month={m}&day={d}&count={c}&skip={s}",
          day.getYear(), day.getMonthValue(), day.getDayOfMonth(), RECON_PAGE_SIZE, page * RECON_PAGE_SIZE).headers(gateway::auth)
          .retrieve().body(JsonNode.class);
      JsonNode rows = res == null ? null : res.path("items");
      if (rows == null) break;
      rows.forEach(r -> {
        if (settlementId.equals(r.path("settlement_id").asString(""))) items.add(item(r, summary));
      });
      if (rows.size() < RECON_PAGE_SIZE) break;
    }
    return Optional.of(new Detail(summary, items));
  }

  @Override
  public Optional<PaymentDetail> fetchPaymentDetail(String providerRef) {
    JsonNode p;
    try {
      p = gateway.client().get().uri("/v1/payments/{id}", providerRef).headers(gateway::auth).retrieve().body(JsonNode.class);
    } catch (HttpClientErrorException.NotFound e) {
      return Optional.empty();
    }
    if (p == null || p.path("id").asString(null) == null) return Optional.empty();
    JsonNode fee = p.path("fee");
    long tax = p.path("tax").asLong(0);
    return Optional.of(new PaymentDetail(blank(p.path("method").asString(null)), blank(p.path("vpa").asString(null)),
        blank(p.path("card").path("network").asString(null)), blank(p.path("card").path("last4").asString(null)),
        blank(p.path("bank").asString(null)), fee.isNumber() ? fee.asLong() - tax : null, fee.isNumber() ? tax : null));
  }

  private static String blank(String s) { return s == null || s.isBlank() ? null : s; }

  private static Summary summary(JsonNode n) {
    long fees = n.path("fees").asLong(0);
    long tax = n.path("tax").asLong(0);
    long net = n.path("amount").asLong(0);
    Instant at = Instant.ofEpochSecond(n.path("created_at").asLong(0));
    return new Summary(n.path("id").asString(), blank(n.path("utr").asString(null)), net + fees, fees - tax, tax, net,
        n.path("status").asString("processed"), at);
  }

  /** One row of the combined reconciliation report: a payment, refund, adjustment or transfer that moved money in the settlement. */
  private static Item item(JsonNode r, Summary s) {
    String type = r.path("type").asString("");
    ItemKind kind = switch (type) {
      case "payment" -> ItemKind.PAYMENT;
      case "refund" -> ItemKind.REFUND;
      default -> ItemKind.ADJUSTMENT;
    };
    long fee = r.path("fee").asLong(0);
    long tax = r.path("tax").asLong(0);
    long gross = kind == ItemKind.REFUND ? r.path("debit").asLong(0) : r.path("credit").asLong(0);
    if (kind == ItemKind.ADJUSTMENT) gross = Math.max(r.path("credit").asLong(0), r.path("debit").asLong(0));
    long net = kind == ItemKind.PAYMENT ? gross - fee : kind == ItemKind.REFUND ? -gross : r.path("credit").asLong(0) - r.path("debit").asLong(0);
    long settledEpoch = r.path("settled_at").asLong(0);
    String entityId = r.path("entity_id").asString();
    String paymentId = blank(r.path("payment_id").asString(null));
    if (paymentId == null && kind == ItemKind.PAYMENT) paymentId = entityId;
    return new Item(entityId, kind, paymentId,
        kind == ItemKind.REFUND ? entityId : null, gross, Math.max(0, fee - tax), tax, net,
        blank(r.path("method").asString(null)), settledEpoch > 0 ? Instant.ofEpochSecond(settledEpoch) : s.settledAt(),
        blank(r.path("settlement_utr").asString(null)) == null ? s.utr() : r.path("settlement_utr").asString(), null);
  }
}
