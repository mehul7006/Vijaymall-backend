package com.vijay.mall.payment.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.payment.domain.CardTerminal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Finds the card terminal adapter by provider name. Adding a brand = one new class that implements {@link CardTerminal}. */
@Component
public class CardTerminalRegistry {
  private final Map<String, CardTerminal> byName;

  /** Creates the registry from every terminal bean. */
  public CardTerminalRegistry(List<CardTerminal> terminals) {
    this.byName = terminals.stream().collect(Collectors.toMap(CardTerminal::provider, Function.identity()));
  }

  /** The terminal with this name; {@code 503 TERMINAL_NOT_CONFIGURED} when there is none or it is not set up. */
  public CardTerminal byProvider(String name) {
    CardTerminal t = byName.get(name == null ? "" : name.strip().toLowerCase());
    if (t == null || !t.configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "TERMINAL_NOT_CONFIGURED", "The card terminal is not set up");
    return t;
  }
}
