package com.vijay.mall.payment.infra;

import com.vijay.mall.payment.domain.SettlementProvider;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The generic provider for money no API reports: card machine statements, UPI collections and bank statements. A person uploads a CSV
 * (see {@code SettlementService#importCsv}); nothing is pulled, so every method answers "nothing".
 */
@Component
public class ManualSettlementProvider implements SettlementProvider {
  @Override public String provider() { return "manual"; }

  @Override public boolean configured() { return true; }

  @Override public boolean pulls() { return false; }

  @Override public Listing listSettlements(Instant from, Instant to, String cursor) { return new Listing(List.of(), null); }

  @Override public Optional<Detail> settlementDetail(String settlementId) { return Optional.empty(); }

  @Override public Optional<PaymentDetail> fetchPaymentDetail(String providerRef) { return Optional.empty(); }
}
