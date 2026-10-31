package com.vijay.mall.wallet.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CoinRuleAndRedemptionBoundaryTest {
  private static final CoinSettings HALF = new CoinSettings(100, 50, 12);

  // ---------- CoinRule ----------
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void ruleNeedsAName(String name) {
    assertThatThrownBy(() -> new CoinRule(name, 1, 1, CoinMode.SLAB, 0))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("name is required");
  }

  @ParameterizedTest
  @CsvSource({"0,1,spendPaise must be positive", "-5,1,spendPaise must be positive",
      "1,0,coins must be positive", "1,-1,coins must be positive"})
  void ruleNeedsPositiveSpendAndCoins(long spend, long coins, String message) {
    assertThatThrownBy(() -> new CoinRule("r", spend, coins, CoinMode.PRO_RATA, 0))
        .isInstanceOf(IllegalArgumentException.class).hasMessage(message);
  }

  @Test
  void ruleNeedsAModeAndNonNegativeMinimum() {
    assertThatThrownBy(() -> new CoinRule("r", 1, 1, null, 0)).hasMessage("mode is required");
    assertThatThrownBy(() -> new CoinRule("r", 1, 1, CoinMode.SLAB, -1)).hasMessage("minOrderPaise cannot be negative");
    assertThat(new CoinRule("r", 1, 1, CoinMode.SLAB, 0).minOrderPaise()).isZero();
  }

  // ---------- CoinRedemption ----------
  @ParameterizedTest(name = "{0} redeemable={1} pct={2} balance={3} -> {4}")
  @CsvSource({
      "ALL,100000,0,900,500",       // 50% of 1000.00
      "ALL,100000,0,500,500",       // balance exactly the cap
      "ALL,100000,0,499,499",       // balance one coin short of the cap
      "ALL,100000,0,0,0",
      "ALL,100000,0,-3,0",
      "ALL,0,0,900,0",
      "ALL,-100,0,900,0",
      "ALL,1999,0,900,9",           // cap 999 paise -> 9 whole coins
      "ALL,199,0,900,0",            // cap 99 paise -> not even one coin
      "NONE,100000,100,900,0",
      "PERCENT,100000,0,900,0",
      "PERCENT,100000,-10,900,0",   // negative percent clamps to 0
      "PERCENT,100000,1,900,5",
      "PERCENT,100000,33,900,165",
      "PERCENT,100000,100,900,500",
      "PERCENT,100000,101,900,500", // over 100 clamps to 100
      "PERCENT,100000,50,100,100"   // balance limits the wanted 250
  })
  void coinsToUseBoundaries(CoinRedemption.Mode mode, long redeemable, int pct, int balance, int expected) {
    assertThat(CoinRedemption.coinsToUse(redeemable, mode, pct, balance, HALF)).isEqualTo(expected);
  }

  @Test
  void settingsDecideTheValueAndTheCap() {
    assertThat(CoinRedemption.coinsToUse(100, CoinRedemption.Mode.ALL, 0, 1000, new CoinSettings(3, 100, 12))).isEqualTo(33);
    assertThat(CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.ALL, 0, 1000, new CoinSettings(100, 0, 12))).isZero();
    assertThat(CoinRedemption.coinsToUse(100000, CoinRedemption.Mode.ALL, 0, 1000, new CoinSettings(100, 100, 12))).isEqualTo(1000);
  }

  // ---------- StoredRule ----------
  private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant END = Instant.parse("2026-11-30T23:59:59Z");
  private static final CoinRule RULE = new CoinRule("r", 100, 1, CoinMode.SLAB, 0);

  @ParameterizedTest(name = "active={0} now={1} -> live={2}")
  @CsvSource({
      "true,2026-10-31T23:59:59Z,false",  // one second before the window
      "true,2026-11-01T00:00:00Z,true",   // window start is inclusive
      "true,2026-11-15T12:00:00Z,true",
      "true,2026-11-30T23:59:59Z,true",   // window end is inclusive
      "true,2026-12-01T00:00:00Z,false",
      "false,2026-11-15T12:00:00Z,false"  // switched off wins over the window
  })
  void windowAndActiveFlag(boolean active, Instant now, boolean live) {
    assertThat(new StoredRule(UUID.randomUUID(), RULE, START, END, active).isLive(now)).isEqualTo(live);
  }

  @Test
  void openEndedWindowsAreAlwaysInside() {
    Instant far = Instant.parse("2100-01-01T00:00:00Z");
    assertThat(new StoredRule(null, RULE, null, null, true).isLive(far)).isTrue();
    assertThat(new StoredRule(null, RULE, START, null, true).isLive(far)).isTrue();
    assertThat(new StoredRule(null, RULE, START, null, true).isLive(START.minusSeconds(1))).isFalse();
    assertThat(new StoredRule(null, RULE, null, END, true).isLive(Instant.EPOCH)).isTrue();
    assertThat(List.of(RULE)).hasSize(1);
  }
}
