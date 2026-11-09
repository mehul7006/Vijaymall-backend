package com.vijay.mall.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Test-only providers for the bank and settlement integration tests: a payment gateway, a settlement source and a card terminal. */
@TestConfiguration
public class BankFakes {
  @Bean FakeGateway fakeGateway() { return new FakeGateway(); }

  @Bean FakeSettlementProvider fakeSettlementProvider() { return new FakeSettlementProvider(); }

  @Bean FakeTerminal fakeTerminal() { return new FakeTerminal(); }
}
