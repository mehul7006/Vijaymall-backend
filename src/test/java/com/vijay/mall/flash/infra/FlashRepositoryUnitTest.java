package com.vijay.mall.flash.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The edges of FlashRepository that need no database: empty id lists never query, and an unknown status is refused. */
class FlashRepositoryUnitTest {
  JdbcClient jdbc = mock(JdbcClient.class);
  FlashRepository repo = new FlashRepository(jdbc);

  @Test
  void emptyIdListsNeverTouchTheDatabase() {
    assertThat(repo.clashes(List.of(), Instant.now(), Instant.now(), UUID.randomUUID())).isEmpty();
    assertThat(repo.totalsBySale(List.of())).isEmpty();
    assertThat(repo.bestItems(List.of(), 3)).isEmpty();
    assertThat(repo.customersAcross(List.of())).isZero();
    verifyNoInteractions(jdbc);
  }

  @Test
  void anUnknownStatusIsRefused() {
    assertThatThrownBy(() -> repo.list("BOGUS", Instant.now(), 10, 0)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BOGUS");
    assertThatThrownBy(() -> repo.count("BOGUS", Instant.now())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void soldOutIsReportedOnlyWhenARowChanged() {
    JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    when(jdbc.sql(ArgumentMatchers.anyString())).thenReturn(spec);
    when(spec.update()).thenReturn(0, 1);
    UUID item = UUID.randomUUID();
    assertThat(repo.markSoldOutNotified(item, Instant.now())).isFalse();
    assertThat(repo.markSoldOutNotified(item, Instant.now())).isTrue();
  }
}
