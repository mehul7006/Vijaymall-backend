package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vijay.mall.identity.infra.AddressRepository;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileRecipientsTest {
  private final UserRepository users = mock(UserRepository.class);
  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final ProfileService profiles =
      new ProfileService(users, mock(AddressRepository.class), devices, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  private final List<String> roles = List.of("CUSTOMER", "ADMIN", "CASHIER");

  @Test
  void idsAndMobilesAreResolvedDeduplicatedAndTheRestIsSkipped() {
    UUID a = new UUID(0, 2);
    UUID b = new UUID(0, 1);
    UUID blocked = new UUID(0, 3);
    when(users.eligibleIds(any(), any())).thenReturn(List.of(a, b));
    when(users.eligibleIdsByMobile(any(), any())).thenReturn(Map.of("9876543210", List.of(b, new UUID(0, 4))));

    var r = profiles.resolveRecipients(List.of(a, b, a, blocked), List.of("9876543210", "9876543210", "1111111111"));

    assertThat(r.ids()).containsExactly(b, a, new UUID(0, 4));
    assertThat(r.skipped()).containsExactly(blocked.toString(), "1111111111");
    verify(users).eligibleIds(Set.of(a, b, blocked), roles);
  }

  @Test
  void emptyInputsDoNotTouchTheDatabase() {
    var r = profiles.resolveRecipients(List.of(), List.of());
    assertThat(r.ids()).isEmpty();
    assertThat(r.skipped()).isEmpty();
    verifyNoInteractions(users);
    assertThat(profiles.deviceTokensOf(List.of())).isEmpty();
    verifyNoInteractions(devices);
  }

  @Test
  void groupQueriesPassStraightThrough() {
    UUID after = new UUID(0, 0);
    when(users.recipientIdsAfter(List.of("ADMIN"), after, 5)).thenReturn(List.of(new UUID(0, 1)));
    when(users.countRecipients(List.of("ADMIN"))).thenReturn(7L);
    when(users.countRecipientsWithDevices(List.of("ADMIN"))).thenReturn(3L);
    when(devices.tokensOfAll(List.of(new UUID(0, 1)))).thenReturn(List.of("tok"));
    assertThat(profiles.recipientIdsAfter(List.of("ADMIN"), after, 5)).containsExactly(new UUID(0, 1));
    assertThat(profiles.countRecipients(List.of("ADMIN"))).isEqualTo(7);
    assertThat(profiles.countRecipientsWithDevices(List.of("ADMIN"))).isEqualTo(3);
    assertThat(profiles.deviceTokensOf(List.of(new UUID(0, 1)))).containsExactly("tok");
  }
}
