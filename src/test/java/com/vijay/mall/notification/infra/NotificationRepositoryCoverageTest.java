package com.vijay.mall.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
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
    when(jpa.findByUserId(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity(NOW), entity(null))));
    var page = repo.page(user, 0, 20);
    assertThat(page.items()).extracting(NotificationRepository.Row::read).containsExactly(true, false);
    assertThat(page.total()).isEqualTo(2);
  }

  @Test void markReadAndMarkAllRead_goToTheQueries() {
    UUID user = UUID.randomUUID(), id = UUID.randomUUID();
    repo.markRead(user, id, NOW);
    verify(jpa).markRead(id, user, NOW);
    repo.markAllRead(user, NOW);
    verify(jpa).markAllRead(user, NOW);
  }
}
