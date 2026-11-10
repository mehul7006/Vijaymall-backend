package com.vijay.mall.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.persistence.BoundedCount;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class NotificationRepositoryCoverageTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final NotificationJpa jpa = mock(NotificationJpa.class);
  private final NotificationRepository repo = new NotificationRepository(jpa);

  private static NotificationEntity entity(Instant readAt) {
    NotificationEntity e = new NotificationEntity();
    e.id = UUID.randomUUID();
    e.title = "t";
    e.body = "b";
    e.kind = "ORDER";
    e.ref = "VM-1";
    e.readAt = readAt;
    e.createdAt = NOW;
    return e;
  }

  @Test void pageMarksReadAndUnreadRows() {
    UUID user = UUID.randomUUID();
    when(jpa.findByUserId(any(), any(Pageable.class))).thenReturn(List.of(entity(NOW), entity(null)));
    var page = repo.page(user, 0, 20);
    assertThat(page.items()).extracting(NotificationRepository.Row::read).containsExactly(true, false);
    assertThat(page.total()).isEqualTo(2);
    assertThat(page.totalExact()).isTrue();
    assertThat(page.hasMore()).isFalse();
    verify(jpa, never()).countUpTo(any(), anyInt());
  }

  @Test void pageWithAnExtraRowCountsAndSaysMoreFollow() {
    UUID user = UUID.randomUUID();
    when(jpa.findByUserId(any(), any(Pageable.class))).thenReturn(List.of(entity(null), entity(null), entity(null)));
    when(jpa.countUpTo(user, BoundedCount.CAP + 1)).thenReturn(7L);
    var page = repo.page(user, 0, 2);
    assertThat(page.items()).hasSize(2);
    assertThat(page.hasMore()).isTrue();
    assertThat(page.total()).isEqualTo(7);
    assertThat(page.totalExact()).isTrue();
  }

  @Test void hugeInboxReportsTheCapAsInexact() {
    UUID user = UUID.randomUUID();
    when(jpa.findByUserId(any(), any(Pageable.class))).thenReturn(List.of(entity(null), entity(null)));
    when(jpa.countUpTo(user, BoundedCount.CAP + 1)).thenReturn((long) BoundedCount.CAP + 1);
    var page = repo.page(user, 0, 1);
    assertThat(page.total()).isEqualTo(BoundedCount.CAP);
    assertThat(page.totalExact()).isFalse();
    assertThat(page.hasMore()).isTrue();
  }

  @Test void markReadAndMarkAllRead_goToTheQueries() {
    UUID user = UUID.randomUUID(), id = UUID.randomUUID();
    repo.markRead(user, id, NOW);
    verify(jpa).markRead(id, user, NOW);
    repo.markAllRead(user, NOW);
    verify(jpa).markAllRead(user, NOW);
  }
}
