package com.vijay.mall.identity.infra;

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
import org.mockito.ArgumentCaptor;

class DeviceRepositoryTest {
  private final DeviceJpa jpa = mock(DeviceJpa.class);
  private final DeviceRepository repo = new DeviceRepository(jpa);
  private final UUID user = UUID.randomUUID();
  private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

  @Test
  void aNewTokenGetsARowAndAKnownTokenMovesToTheNewUser() {
    when(jpa.findByFcmToken("t1")).thenReturn(Optional.empty());
    repo.upsert(user, "t1", "ANDROID", now);
    var saved = ArgumentCaptor.forClass(DeviceTokenEntity.class);
    verify(jpa).save(saved.capture());
    assertThat(saved.getValue().fcmToken).isEqualTo("t1");
    assertThat(saved.getValue().userId).isEqualTo(user);
    assertThat(saved.getValue().createdAt).isEqualTo(now);

    var existing = saved.getValue();
    UUID other = UUID.randomUUID();
    when(jpa.findByFcmToken("t1")).thenReturn(Optional.of(existing));
    repo.upsert(other, "t1", "IOS", now);
    assertThat(existing.userId).isEqualTo(other);
    assertThat(existing.platform).isEqualTo("IOS");
  }

  @Test
  void tokensCanBeListedAndForgotten() {
    when(jpa.tokensOf(user)).thenReturn(List.of("a", "b"));
    assertThat(repo.tokensOf(user)).containsExactly("a", "b");
    repo.deleteAll(user);
    verify(jpa).deleteAllOf(user);
    repo.deleteToken("dead");
    verify(jpa).deleteByToken("dead");
  }
}
