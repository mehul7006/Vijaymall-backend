package com.vijay.mall.support;

import com.vijay.mall.payment.domain.SettlementProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A programmable settlement source used only in tests: tests fill {@link #settlements} and {@link #details} and set {@link #configured}. */
public class FakeSettlementProvider implements SettlementProvider {
  public final List<Summary> settlements = new ArrayList<>();
  public final Map<String, Detail> details = new LinkedHashMap<>();
  public final Map<String, PaymentDetail> paymentDetails = new LinkedHashMap<>();
  public volatile boolean configured = true;
  public volatile boolean failListing;
  /** When true the listing is cut into pages of one so cursor handling is exercised. */
  public volatile boolean onePerPage;
  public volatile int detailCalls;

  /** Forgets everything a test set. */
  public void reset() {
    settlements.clear();
    details.clear();
    paymentDetails.clear();
    configured = true;
    failListing = false;
    onePerPage = false;
    detailCalls = 0;
  }

  @Override public String provider() { return "fake"; }

  @Override public boolean configured() { return configured; }

  @Override
  public Listing listSettlements(Instant from, Instant to, String cursor) {
    if (failListing) throw new IllegalStateException("provider down");
    if (!onePerPage) return new Listing(List.copyOf(settlements), null);
    int i = cursor == null ? 0 : Integer.parseInt(cursor);
    return new Listing(i < settlements.size() ? List.of(settlements.get(i)) : List.of(), i + 1 < settlements.size() ? Integer.toString(i + 1) : null);
  }

  @Override
  public Optional<Detail> settlementDetail(String settlementId) {
    detailCalls++;
    if (settlementId.startsWith("boom")) throw new IllegalStateException("provider down");
    return Optional.ofNullable(details.get(settlementId));
  }

  @Override
  public Optional<PaymentDetail> fetchPaymentDetail(String providerRef) {
    if ("boom".equals(providerRef)) throw new IllegalStateException("provider down");
    return Optional.ofNullable(paymentDetails.get(providerRef));
  }
}
