package com.vijay.mall.order.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderReturnRepositoryTest {
  private final OrderReturnJpa returns = mock(OrderReturnJpa.class);
  private final OrderReturnItemJpa lines = mock(OrderReturnItemJpa.class);
  private final OrderItemJpa orderItems = mock(OrderItemJpa.class);
  private final OrderReturnRepository repo = new OrderReturnRepository(returns, lines, orderItems);
  private final UUID id = UUID.randomUUID();

  @Test
  void anOrderWithoutReturnsDoesNotLoadAnyLines() {
    when(returns.findByOrderIdOrderByCreatedAtAsc(id)).thenReturn(List.of());
    assertThat(repo.forOrder(id)).isEmpty();
    verifyNoInteractions(lines);
  }

  @Test
  void addReturnedIsTrueOnlyWhenExactlyOneRowMoved() {
    when(orderItems.addReturned(id, 2)).thenReturn(1);
    when(orderItems.addReturned(id, 9)).thenReturn(0);
    assertThat(repo.addReturned(id, 2)).isTrue();
    assertThat(repo.addReturned(id, 9)).isFalse();
  }
}
