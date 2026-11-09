package com.vijay.mall.pos.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Which products had their counter attributes or barcodes written since an instant. */
class PosCatalogChangedSinceTest {
  @Test void unionsProductsWithNewAttributesAndWithNewBarcodes() {
    PosProductJpa pos = mock(PosProductJpa.class);
    PosBarcodeJpa barcodes = mock(PosBarcodeJpa.class);
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    Instant since = Instant.parse("2026-10-01T00:00:00Z");
    when(pos.changedSince(since)).thenReturn(List.of(a, b));
    when(barcodes.changedSince(since)).thenReturn(List.of(b));
    assertThat(new PosCatalogRepository(pos, barcodes).changedSince(since)).isEqualTo(Set.of(a, b));
  }

  @Test void entryOfPaymentMapsTheLedgerLine() {
    CreditLedgerJpa ledger = mock(CreditLedgerJpa.class);
    UUID payment = UUID.randomUUID();
    when(ledger.findFirstByPaymentId(payment)).thenReturn(Optional.empty());
    var repo = new CreditRepository(mock(CreditJpa.class), ledger, mock(CreditPaymentJpa.class), mock(CreditPaymentLineJpa.class));
    assertThat(repo.entryOfPayment(payment)).isEmpty();
    CreditLedgerEntity e = new CreditLedgerEntity();
    e.id = UUID.randomUUID();
    e.kind = "PAYMENT";
    e.deltaPaise = -100;
    e.balanceAfterPaise = 400;
    when(ledger.findFirstByPaymentId(payment)).thenReturn(Optional.of(e));
    assertThat(repo.entryOfPayment(payment)).get().extracting(CreditRepository.Entry::balanceAfterPaise).isEqualTo(400L);
  }
}
