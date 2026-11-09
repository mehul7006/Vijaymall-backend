package com.vijay.mall.payment.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.CardTerminal;
import com.vijay.mall.payment.infra.ManualSettlementProvider;
import com.vijay.mall.payment.infra.ManualTerminal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RegistriesTest {
  @Test void settlementRegistryFindsByNameAndListsOnlyPullableConfiguredProviders() {
    var manual = new ManualSettlementProvider();
    var registry = new SettlementProviderRegistry(List.of(manual));
    assertThat(registry.byProvider(" MANUAL ")).isSameAs(manual);
    assertThat(registry.find("manual")).isSameAs(manual);
    assertThat(registry.find("x")).isNull();
    assertThat(registry.pullable()).isEmpty();
    assertThatThrownBy(() -> registry.byProvider("x")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> registry.byProvider(null)).isInstanceOf(ApiException.class);
  }

  @Test void manualSettlementProviderKnowsNothing() {
    var p = new ManualSettlementProvider();
    assertThat(p.configured()).isTrue();
    assertThat(p.pulls()).isFalse();
    assertThat(p.listSettlements(Instant.EPOCH, Instant.now(), null).items()).isEmpty();
    assertThat(p.settlementDetail("x")).isEmpty();
    assertThat(p.fetchPaymentDetail("x")).isEmpty();
  }

  @Test void terminalRegistryRefusesUnknownOrUnconfiguredTerminals() {
    var manual = new ManualTerminal();
    var registry = new CardTerminalRegistry(List.of(manual));
    assertThat(registry.byProvider("Manual")).isSameAs(manual);
    assertThatThrownBy(() -> registry.byProvider("paytm")).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("TERMINAL_NOT_CONFIGURED"));
    assertThatThrownBy(() -> registry.byProvider(null)).isInstanceOf(ApiException.class);
  }

  @Test void manualTerminalTellsTheCashierWhatToDo() {
    var t = new ManualTerminal();
    var s = t.requestPayment(100005, "R1");
    assertThat(s.state()).isEqualTo(CardTerminal.State.PENDING);
    assertThat(s.mode()).isEqualTo(CardTerminal.Mode.MANUAL);
    assertThat(s.instructions()).contains("1000.05");
    assertThat(t.status("R1").state()).isEqualTo(CardTerminal.State.PENDING);
    assertThat(t.cancel("R1").state()).isEqualTo(CardTerminal.State.CANCELLED);
    assertThat(t.configured()).isTrue();
    assertThat(t.manualConfirmation()).isTrue();
  }
}
