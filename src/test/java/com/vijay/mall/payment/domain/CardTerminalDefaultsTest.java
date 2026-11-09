package com.vijay.mall.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.payment.domain.CardTerminal.Request;
import com.vijay.mall.payment.infra.ManualTerminal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A terminal that keeps no sessions of its own inherits harmless defaults for the optional parts of the port. */
class CardTerminalDefaultsTest {
  private final CardTerminal manual = new ManualTerminal();

  @Test void aSimpleTerminalIgnoresTheChannelAndKeepsNoSessionsOfItsOwn() {
    CardTerminal.Session s = manual.requestPayment(new Request(UUID.randomUUID(), 12345, "R1", UUID.randomUUID(), "T"));
    assertThat(s.id()).isNull();
    assertThat(s.detail()).isNull();
    assertThat(s.reference()).isEqualTo("R1");
    assertThat(s.instructions()).contains("123.45");
    assertThat(manual.find(UUID.randomUUID())).isEmpty();
    assertThat(manual.attach(UUID.randomUUID(), UUID.randomUUID(), 1, "CARD", "f", null)).isEmpty();
    assertThatThrownBy(() -> manual.cancel(UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> manual.voidPayment(UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(UnsupportedOperationException.class);
  }
}
