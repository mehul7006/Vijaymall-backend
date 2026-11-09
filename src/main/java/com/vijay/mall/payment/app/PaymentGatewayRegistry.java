package com.vijay.mall.payment.app;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.domain.PaymentGateway;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Creates the registry from every gateway bean and the configured ONLINE provider name. */
  public PaymentGatewayRegistry(List<PaymentGateway> gateways, AppProps props) {
    this.byName = gateways.stream().collect(Collectors.toMap(PaymentGateway::provider, Function.identity()));
    this.onlineProvider = props.payment().onlineProvider();
  }

  /** The gateway with this provider name (case-insensitive); {@code 404} if there is none. */
  public PaymentGateway byProvider(String name) {
    PaymentGateway g = byName.get(name == null ? "" : name.toLowerCase());
    if (g == null) throw ApiException.notFound("Payment provider");
    return g;
  }

  private RuntimeSettings settings;

  /** Optional (plain unit tests run without it): the admin's choice of the online gateway wins over the server default. */
  @Autowired(required = false)
  void setSettings(RuntimeSettings settings) { this.settings = settings; }

  private String chosen() {
    String s = settings == null ? "" : settings.get(Settings.ONLINE_PROVIDER).strip().toLowerCase();
    return byName.containsKey(s) ? s : onlineProvider;
  }

  /** True when the ONLINE provider exists and has its keys, so customers can really pay with it. */
  public boolean onlineConfigured() {
    PaymentGateway g = byName.get(chosen());
    return g != null && g.configured();
  }

  /** The provider used for method ONLINE. */
  public PaymentGateway online() {
    PaymentGateway g = byName.get(chosen());
    if (g == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_NOT_CONFIGURED", "Online payment is not configured");
    return g;
  }
}
