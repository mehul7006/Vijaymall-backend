package com.vijay.mall.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.AppProps;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.PaymentGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class PaymentGatewayRegistryCoverageTest {
  private static PaymentGateway gateway(String name, boolean configured) {
    var g = mock(PaymentGateway.class);
    when(g.provider()).thenReturn(name);
    when(g.configured()).thenReturn(configured);
    return g;
  }

  private static PaymentGatewayRegistry registry(String online, PaymentGateway... gws) {
    var props = new AppProps(null, null, null, null, new AppProps.Payment(online, 15, null, null, null, null), null);
    return new PaymentGatewayRegistry(List.of(gws), props);
  }

  @Test
  void onlineIsNotConfiguredWhenTheProviderHasNoKeys() {
    assertThat(registry("razorpay", gateway("razorpay", false)).onlineConfigured()).isFalse();
  }

  @Test
  void onlineIsConfiguredWhenTheProviderHasKeys() {
    assertThat(registry("razorpay", gateway("razorpay", true)).onlineConfigured()).isTrue();
  }

  @Test
  void onlineIsNotConfiguredWhenTheProviderDoesNotExist() {
    assertThat(registry("razorpay", gateway("cod", true)).onlineConfigured()).isFalse();
  }

  @Test
  void onlineAndByProviderBehaveWhenTheProviderIsMissing() {
    var reg = registry("razorpay", gateway("cod", true));
    assertThatThrownBy(reg::online).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
      assertThat(e.code()).isEqualTo("PAYMENT_NOT_CONFIGURED");
    });
    assertThatThrownBy(() -> reg.byProvider(null)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    assertThat(reg.byProvider("COD").provider()).isEqualTo("cod");
  }
}
