package com.vijay.mall.inventory.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.persistence.BoundedCount;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
    when(movements.findPageBy(any(Pageable.class))).thenReturn(List.of(row(p)));
    var page = repo.movementPage(null, 0, 10);
    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).productId()).isEqualTo(p);
    assertThat(page.items().get(0).delta()).isEqualTo(-2);
    assertThat(page.total()).as("a short page is the whole list: no count query").isEqualTo(1);
    assertThat(page.totalExact()).isTrue();
    verify(movements, never()).countUpTo(anyInt());
  }

  @Test void movementHistoryOfOneProduct() {
    UUID p = UUID.randomUUID();
    when(movements.findPageByProductId(any(), any(Pageable.class))).thenReturn(List.of(row(p)));
    assertThat(repo.movementPage(p, 0, 10).items()).hasSize(1);
    verify(movements).findPageByProductId(any(), any(Pageable.class));
  }

  @Test void aFullPageIsCountedButNeverBeyondTheCap() {
    UUID p = UUID.randomUUID();
    var eleven = java.util.stream.IntStream.range(0, 11).mapToObj(i -> row(p)).toList();
    when(movements.findPageBy(any(Pageable.class))).thenReturn(eleven);
    when(movements.countUpTo(BoundedCount.CAP + 1)).thenReturn(4_321L);
    var page = repo.movementPage(null, 0, 10);
    assertThat(page.items()).as("the extra row only proves another page follows").hasSize(10);
    assertThat(page.total()).isEqualTo(4_321);
    assertThat(page.totalExact()).isTrue();
    assertThat(page.hasMore()).isTrue();
    when(movements.countUpTo(BoundedCount.CAP + 1)).thenReturn((long) BoundedCount.CAP + 1);
    var big = repo.movementPage(null, 0, 10);
    assertThat(big.total()).isEqualTo(BoundedCount.CAP);
    assertThat(big.totalExact()).isFalse();
    when(movements.findPageByProductId(any(), any(Pageable.class))).thenReturn(eleven);
    when(movements.countUpToByProduct(p, BoundedCount.CAP + 1)).thenReturn(77L);
    assertThat(repo.movementPage(p, 0, 10).total()).isEqualTo(77);
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
