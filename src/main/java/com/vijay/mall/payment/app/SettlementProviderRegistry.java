package com.vijay.mall.payment.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.SettlementProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Finds the settlement adapter by provider name. Adding a brand = one new class that implements {@link SettlementProvider}. */
@Component
public class SettlementProviderRegistry {
  private final Map<String, SettlementProvider> byName;

  /** Creates the registry from every settlement provider bean. */
  public SettlementProviderRegistry(List<SettlementProvider> providers) {
    this.byName = providers.stream().collect(Collectors.toMap(SettlementProvider::provider, Function.identity()));
  }

  /** The provider with this name (case-insensitive); {@code 404} if there is none. */
  public SettlementProvider byProvider(String name) {
    SettlementProvider p = byName.get(name == null ? "" : name.strip().toLowerCase());
    if (p == null) throw ApiException.notFound("Settlement provider");
    return p;
  }

  /** Providers the daily job pulls from: set up, and fed by an API rather than by a person. */
  public List<SettlementProvider> pullable() {
    return byName.values().stream().filter(SettlementProvider::pulls).filter(SettlementProvider::configured).toList();
  }

  /** The provider if there is one with this name, else null. */
  public SettlementProvider find(String name) { return byName.get(name); }
}
