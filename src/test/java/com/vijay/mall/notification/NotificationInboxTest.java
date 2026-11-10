package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.app.NotificationService;
import com.vijay.mall.notification.app.PushSender;
import com.vijay.mall.notification.infra.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class NotificationInboxTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final NotificationRepository repo = mock(NotificationRepository.class);
  private final NotificationService service = new NotificationService(repo, mock(PushSender.class),
      mock(ProfileService.class), JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID user = UUID.randomUUID();

  @Test
  void inboxReturnsAPageAndTheUnreadCount() {
    var row = new NotificationRepository.Row(UUID.randomUUID(), "Order update", "Order VM-7 is now packed.", "ORDER", "VM-7", false, NOW);
    when(repo.page(user, 0, 20)).thenReturn(new Page<>(List.of(row), 0, 20, 1));
    when(repo.unread(user)).thenReturn(1L);
    var inbox = service.inbox(user, -1, 20);
    assertThat(inbox.items()).containsExactly(row);
    assertThat(inbox.unread()).isEqualTo(1);
    assertThat(inbox.total()).isEqualTo(1);
    assertThat(inbox.totalExact()).isTrue();
    assertThat(inbox.hasMore()).isFalse();
  }

  @Test
  void inboxPassesOnTheInexactTotalAndHasMore() {
    when(repo.page(user, 2, 20)).thenReturn(new Page<>(List.of(), 2, 20, 10_000, false, true));
    when(repo.unread(user)).thenReturn(0L);
    var inbox = service.inbox(user, 2, 20);
    assertThat(inbox.totalExact()).isFalse();
    assertThat(inbox.hasMore()).isTrue();
    assertThat(inbox.total()).isEqualTo(10_000);
  }

  @Test
  void markReadMarksTheOwnNotification() {
    UUID id = UUID.randomUUID();
    when(repo.owns(user, id)).thenReturn(true);
    service.markRead(user, id);
    verify(repo).markRead(user, id, NOW);
  }

  @Test
  void someoneElsesNotificationIsNotFound() {
    UUID id = UUID.randomUUID();
    when(repo.owns(user, id)).thenReturn(false);
    assertThatThrownBy(() -> service.markRead(user, id)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
    verify(repo, never()).markRead(any(), any(), any());
  }

  @Test
  void markAllReadMarksEverything() {
    service.markAllRead(user);
    verify(repo).markAllRead(user, NOW);
  }
}
