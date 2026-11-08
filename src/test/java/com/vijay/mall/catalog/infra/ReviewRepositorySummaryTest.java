package com.vijay.mall.catalog.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewRepositorySummaryTest {
  private final ReviewJpa jpa = mock(ReviewJpa.class);
  private final ReviewRepository repo = new ReviewRepository(jpa);
  private final UUID id = UUID.randomUUID();

  @Test
  void aMissingAverageCountsAsZeroForOneProductAndForAGroup() {
    when(jpa.countByProductId(id)).thenReturn(0L);
    when(jpa.average(id)).thenReturn(null);
    assertThat(repo.summary(id).average()).isZero();
    when(jpa.average(id)).thenReturn(4.5);
    when(jpa.countByProductId(id)).thenReturn(2L);
    assertThat(repo.summary(id).count()).isEqualTo(2);
    assertThat(repo.summary(id).average()).isEqualTo(4.5);

    when(jpa.averageOf(List.of(id))).thenReturn(null);
    assertThat(repo.summaryOf(List.of(id)).average()).isZero();
    when(jpa.averageOf(List.of(id))).thenReturn(3.0);
    when(jpa.countByProductIdIn(List.of(id))).thenReturn(5L);
    assertThat(repo.summaryOf(List.of(id)).count()).isEqualTo(5);
    assertThat(repo.summaryOf(List.of()).count()).isZero();
  }
}
