package com.vijay.mall.payment.paytm.settlement;

import com.vijay.mall.payment.domain.SettlementProvider;
import com.vijay.mall.payment.paytm.app.PaytmSettings;
import com.vijay.mall.payment.paytm.settlement.PaytmSettlementApiClient.Api;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Paytm settlements for the bank module's registry: the nightly job and "Sync now" pull them through {@link PaytmSettlementApiClient} when
 * {@code paytm.settlement.enabled} is on and the credentials are saved. One settlement is one Paytm payout (its payout id, else its UTR, else its
 * settled date); its items are the lines of {@link PaytmSettlementMapper}. UNTESTED against Paytm: see the client.
 */
@Component
public class PaytmSettlementProvider implements SettlementProvider {
  private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

  private final PaytmSettlementApiClient client;
  private final PaytmSettings cfg;
  private volatile Map<String, Detail> lastListing = Map.of();

  /** Creates the provider. */
  public PaytmSettlementProvider(PaytmSettlementApiClient client, PaytmSettings cfg) {
    this.client = client;
    this.cfg = cfg;
  }

  @Override public String provider() { return "paytm"; }

  /** Set up when the switch is on, the merchant id is saved and the secret of the chosen sign-in style is saved. */
  @Override
  public boolean configured() {
    boolean secret = "CHECKSUM".equals(cfg.settlementAuth()) ? !cfg.merchantKey().isBlank() : !cfg.settlementToken().isBlank();
    return cfg.settlementEnabled() && !cfg.mid().isEmpty() && secret;
  }

  @Override
  public Listing listSettlements(Instant from, Instant to, String cursor) {
    Map<String, Detail> grouped = group(client.fetch(Api.DETAIL, from.atZone(INDIA).toLocalDate(), to.atZone(INDIA).toLocalDate()));
    lastListing = grouped;
    return new Listing(grouped.values().stream().map(Detail::summary).toList(), null);
  }

  @Override
  public Optional<Detail> settlementDetail(String settlementId) {
    Detail known = lastListing.get(settlementId);
    if (known != null) return Optional.of(known);
    lastListing = group(client.sync(Api.DETAIL));
    return Optional.ofNullable(lastListing.get(settlementId));
  }

  /** Paytm's settlement report API has no per-payment lookup. */
  @Override public Optional<PaymentDetail> fetchPaymentDetail(String providerRef) { return Optional.empty(); }

  private static Map<String, Detail> group(List<PaytmSettlementRecord> records) {
    Map<String, List<PaytmSettlementRecord>> byPayout = new LinkedHashMap<>();
    for (PaytmSettlementRecord r : records) {
      if (!PaytmSettlementMapper.paidOut(r)) continue;
      String key = r.payoutId() != null ? r.payoutId() : r.utr() != null ? "utr-" + r.utr() : r.settledDate() != null ? "date-" + r.settledDate() : "unsettled";
      byPayout.computeIfAbsent("paytm-" + key, k -> new ArrayList<>()).add(r);
    }
    Map<String, Detail> out = new LinkedHashMap<>();
    byPayout.forEach((id, rows) -> {
      List<Item> items = rows.stream().map(PaytmSettlementMapper::toItem).toList();
      long gross = items.stream().filter(i -> i.kind() == ItemKind.PAYMENT).mapToLong(Item::grossPaise).sum();
      String utr = rows.stream().map(PaytmSettlementRecord::utr).filter(u -> u != null).findFirst().orElse(null);
      LocalDate settled = rows.stream().map(PaytmSettlementRecord::settledDate).filter(d -> d != null).max(LocalDate::compareTo).orElse(null);
      out.put(id, new Detail(new Summary(id, utr, gross, items.stream().mapToLong(Item::feePaise).sum(), items.stream().mapToLong(Item::taxPaise).sum(),
          items.stream().mapToLong(Item::netPaise).sum(), "processed", settled == null ? null : settled.atStartOfDay(INDIA).toInstant()), items));
    });
    return out;
  }
}
