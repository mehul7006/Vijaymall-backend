package com.vijay.mall.inventory.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class InventoryRepositoryCoverageTest {
  private final StockMovementJpa movements = mock(StockMovementJpa.class);
  private final InventoryJpa stock = mock(InventoryJpa.class);
  private final InventoryRepository repo = new InventoryRepository(stock, movements);

  private static StockMovementEntity row(UUID product) {
    StockMovementEntity m = new StockMovementEntity();
    m.id = UUID.randomUUID();
    m.productId = product;
    m.delta = -2;
    m.reason = "ORDER";
    m.createdAt = Instant.parse("2027-01-01T00:00:00Z");
    return m;
  }

  @Test void movementHistoryOfEveryProduct_whenNoProductIsGiven() {
    UUID p = UUID.randomUUID();
    when(movements.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row(p))));
    var page = repo.movementPage(null, 0, 10);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).productId()).isEqualTo(p);
    assertThat(page.items().get(0).delta()).isEqualTo(-2);
    assertThat(page.total()).isEqualTo(1);
  }

  @Test void movementHistoryOfOneProduct() {
    UUID p = UUID.randomUUID();
    when(movements.findByProductId(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(row(p))));
    assertThat(repo.movementPage(p, 0, 10).items()).hasSize(1);
    verify(movements).findByProductId(any(), any(Pageable.class));
  }

  @Test void aLockedCountReadsTheLockedRowAndIsZeroWithoutOne() {
    UUID p = UUID.randomUUID();
    InventoryEntity e = new InventoryEntity();
    e.productId = p;
    e.qtyOnHand = 12;
    when(stock.lockById(p)).thenReturn(Optional.of(e));
    assertThat(repo.quantityForUpdate(p)).isEqualTo(12);
    assertThat(repo.quantityForUpdate(UUID.randomUUID())).isZero();
  }
}
