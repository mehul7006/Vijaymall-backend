package com.vijay.mall.purchasing.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/** Two requests with one Idempotency-Key at the same moment must leave one purchase (the loser reads the winner's). */
class PurchaseServiceRaceTest {
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

  private static PurchaseRepository.Header stored(UUID id, String hash) {
    return new PurchaseRepository.Header(id, UUID.randomUUID(), "Agro", "", "", "", false, "REGULAR", "", LocalDate.of(2026, 11, 9),
        LocalDate.of(2026, 11, 9), "", "", 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, "PAID", hash, UUID.randomUUID(), NOW);
  }

  @Test
  void theLoserOfARaceAnswersWithTheWinnersPurchase() {
    var repo = mock(PurchaseRepository.class);
    var tx = mock(TransactionTemplate.class);
    var service = new PurchaseService(repo, mock(InventoryService.class), mock(CatalogService.class), mock(AuditService.class), mock(SupplierService.class),
        mock(PosCatalogService.class), mock(ShopService.class), tx, Clock.fixed(NOW, ZoneOffset.UTC), mock(AdminOps.class), mock(RuntimeSettings.class));
    var item = new PurchaseService.ItemInput(UUID.randomUUID(), 1, 10, null, BigDecimal.ZERO, null, null, null, null, null, null, null, null, null);
    var in = new PurchaseService.Input(null, "Agro", null, null, List.of(item), null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null, null);
    UUID id = UUID.randomUUID();
    when(repo.findByIdem("race")).thenReturn(Optional.empty(), Optional.of(stored(id, AdminOps.fingerprint(in))));
    when(tx.execute(any())).thenThrow(new DuplicateKeyException("dup"));

    assertThat(service.receive(UUID.randomUUID(), "race", in).id()).isEqualTo(id);

    when(repo.findByIdem("race")).thenReturn(Optional.empty(), Optional.of(stored(id, "some other request")));
    assertThatThrownBy(() -> service.receive(UUID.randomUUID(), "race", in)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
  }
}
