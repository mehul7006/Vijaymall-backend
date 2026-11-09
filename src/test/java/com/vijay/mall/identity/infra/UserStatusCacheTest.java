package com.vijay.mall.identity.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.cache.AppCaches;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Blocking or unblocking an account drops its cached blocked flag, so the change shows at once. */
class UserStatusCacheTest {
  private final UserJpa jpa = mock(UserJpa.class);
  private final UserRepository repo = new UserRepository(jpa);
  private final UUID id = UUID.randomUUID();

  private UserEntity entity() {
    var e = new UserEntity();
    e.id = id;
    e.status = "ACTIVE";
    return e;
  }

  @Test
  void setStatusEvictsTheCachedAnswer() {
    var caches = new AppCaches(Clock.systemUTC());
    repo.setCaches(caches);
    var e = entity();
    when(jpa.findById(id)).thenReturn(Optional.of(e));
    caches.get(AppCaches.Name.BLOCKED_USERS, id, () -> false);
    repo.setStatus(id, "BLOCKED");
    assertThat(e.status).isEqualTo("BLOCKED");
    assertThat(caches.get(AppCaches.Name.BLOCKED_USERS, id, () -> true)).isTrue();
  }

  @Test
  void setStatusWorksWithoutCachesAndForUnknownUsers() {
    var e = entity();
    when(jpa.findById(id)).thenReturn(Optional.of(e));
    repo.setStatus(id, "BLOCKED");
    assertThat(e.status).isEqualTo("BLOCKED");
    repo.setStatus(UUID.randomUUID(), "BLOCKED"); // unknown user: nothing happens
  }
}
