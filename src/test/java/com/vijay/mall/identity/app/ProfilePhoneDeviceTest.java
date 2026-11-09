package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.AddressRepository;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class ProfilePhoneDeviceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");
  private final UserRepository users = mock(UserRepository.class);
  private final AddressRepository addresses = mock(AddressRepository.class);
  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final ProfileService service = new ProfileService(users, addresses, devices, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID uid = UUID.randomUUID();

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "   \t"})
  void aBlankNameIsRefusedButAMissingNameKeepsTheSavedOne(String name) {
    assertThatThrownBy(() -> service.update(uid, name, null)).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
      assertThat(e.details().toString()).contains("name", "must not be blank");
    });
    verify(users, never()).updateProfile(any(), any(), any(), any());
    when(users.findById(uid)).thenReturn(Optional.of(new User(uid, "a@b.com", "A", null, User.CUSTOMER)));
    service.update(uid, null, null);
    verify(users).updateProfile(uid, null, null, NOW);
  }

  @Test
  void thePhoneIsStoredOnCreateAndEditAndKeptWhenTheDefaultMoves() {
    UUID id = UUID.randomUUID();
    when(addresses.count(uid)).thenReturn(1);
    service.addAddress(uid, new Address(null, "H", "l", "a", "390001", false, "9876543210"));
    verify(addresses).insert(uid, new Address(null, "H", "l", "a", "390001", false, "9876543210"), NOW);

    when(addresses.find(uid, id)).thenReturn(Optional.of(new Address(id, "H", "l", "a", "390001", false)));
    service.updateAddress(uid, id, new Address(null, "H", "l", "a", "390001", false, "9123456789"));
    verify(addresses).update(uid, id, new Address(null, "H", "l", "a", "390001", false, "9123456789"));

    UUID next = UUID.randomUUID();
    when(addresses.find(uid, id)).thenReturn(Optional.of(new Address(id, "H", "l", "a", "390001", true)));
    when(addresses.list(uid)).thenReturn(List.of(new Address(next, "O", "l2", "a2", "390002", false, "9000000001")));
    service.deleteAddress(uid, id);
    verify(addresses).update(uid, next, new Address(next, "O", "l2", "a2", "390002", true, "9000000001"));
  }

  @Test
  void unregisteringADeviceOnlyAsksForTheCallersOwnToken() {
    service.unregisterDevice(uid, "tok");
    verify(devices).deleteOwned(uid, "tok");
  }

  @Test
  void orderSnapshotsWithAndWithoutAPhoneRoundTrip() {
    var json = JsonMapper.builder().build();
    var withPhone = new Address(null, "H", "l", "a", "390001", true, "9876543210");
    assertThat(json.readValue(json.writeValueAsString(withPhone), Address.class)).isEqualTo(withPhone);
    var old = "{\"id\":null,\"label\":\"H\",\"line1\":\"l\",\"area\":\"a\",\"pincode\":\"390001\",\"isDefault\":true}";
    assertThat(json.readValue(old, Address.class).phone()).isNull();
  }
}
