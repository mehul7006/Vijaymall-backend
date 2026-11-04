package com.vijay.mall.payment.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.PaymentGateway;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Finds the adapter by provider name. Adding a provider = one new class, one config value. */
@Component
public class PaymentGatewayRegistry {
  private final Map<String, PaymentGateway> byName;
  private final String onlineProvider;

  public PaymentGatewayRegistry(List<PaymentGateway> gateways, AppProps props) {
    this.byName = gateways.stream().collect(Collectors.toMap(PaymentGateway::provider, Function.identity()));
    this.onlineProvider = props.payment().onlineProvider();
  }

  public PaymentGateway byProvider(String name) {
    PaymentGateway g = byName.get(name == null ? "" : name.toLowerCase());
    if (g == null) throw ApiException.notFound("Payment provider");
    return g;
  }

  /** True when the ONLINE provider exists and has its keys, so customers can really pay with it. */
  public boolean onlineConfigured() {
    PaymentGateway g = byName.get(onlineProvider);
    return g != null && g.configured();
  }

  /** The provider used for method ONLINE. */
  public PaymentGateway online() {
    PaymentGateway g = byName.get(onlineProvider);
    if (g == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_CONFIGURED", "Online payment is not configured");
    return g;
  }
}
