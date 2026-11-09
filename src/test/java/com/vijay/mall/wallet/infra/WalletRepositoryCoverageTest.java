package com.vijay.mall.wallet.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WalletRepositoryCoverageTest {
  private final LedgerJpa ledger = mock(LedgerJpa.class);
  private final WalletRepository repo =
      new WalletRepository(mock(WalletJpa.class), ledger, mock(LotUseJpa.class), mock(RuleJpa.class), mock(SettingJpa.class));

  private CoinLedgerEntity row(Integer remaining) {
    CoinLedgerEntity e = new CoinLedgerEntity();
    e.id = UUID.randomUUID();
    e.remaining = remaining;
    when(ledger.findById(e.id)).thenReturn(Optional.of(e));
    return e;
  }

  @Test void rowsWithoutALotBalance_countAsZeroRemaining() {
    CoinLedgerEntity redeem = row(null);
    assertThat(repo.remaining(redeem.id)).isZero();
    repo.changeLot(redeem.id, 4);
    assertThat(redeem.remaining).isEqualTo(4);
    assertThat(repo.remaining(redeem.id)).isEqualTo(4);
  }

  @Test void changeLotAddsToTheRemainingBalance_andUnknownLotsAreIgnored() {
    CoinLedgerEntity lot = row(10);
    repo.changeLot(lot.id, -3);
    assertThat(lot.remaining).isEqualTo(7);
    repo.changeLot(UUID.randomUUID(), 5); // no such lot: nothing happens
    assertThat(repo.remaining(UUID.randomUUID())).isZero();
  }
}
