package com.vijay.mall.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

class ProfileServiceTest {
  private static final Instant NOW = Instant.parse("2027-03-01T10:00:00Z");

  private final UserRepository users = mock(UserRepository.class);
  private final AddressRepository addresses = mock(AddressRepository.class);
  private final DeviceRepository devices = mock(DeviceRepository.class);
  private final ProfileService service = new ProfileService(users, addresses, devices, Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID uid = UUID.randomUUID();
  private final User user = new User(uid, "a@b.com", "A", "9876543210", User.CUSTOMER);

  private static Address addr(boolean def) { return new Address(null, "Home", "1 Road", "Area", "390001", def); }

  @Test
  void getReturnsUserOrNotFound() {
    when(users.findById(uid)).thenReturn(Optional.of(user));
    assertThat(service.get(uid)).isEqualTo(user);
    assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("NOT_FOUND"));
  }

  @Test
  void updateTrimsNameAndKeepsValidMobile() {
    when(users.findById(uid)).thenReturn(Optional.of(user));
    service.update(uid, "  Asha  ", "9876543210");
    verify(users).updateProfile(uid, "Asha", "9876543210", NOW);
  }

  @Test
  void updateTurnsBlankMobileIntoNullAndKeepsTheNameWhenNoneIsSent() {
    when(users.findById(uid)).thenReturn(Optional.of(user));
    service.update(uid, null, "  ");
    verify(users).updateProfile(uid, null, null, NOW); // null name = the repository keeps the saved one
    service.update(uid, "N", null);
    verify(users).updateProfile(uid, "N", null, NOW);
  }

  @ParameterizedTest
  @ValueSource(strings = {"123", "98765432101", "98765abcde", "+919876543210"})
  void updateRejectsBadMobile(String mobile) {
    assertThatThrownBy(() -> service.update(uid, "A", mobile)).isInstanceOfSatisfying(ApiException.class,
        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    verify(users, never()).updateProfile(any(), any(), any(), any());
  }

  @Test
  void firstAddressBecomesDefaultEvenIfNotRequested() {
    when(addresses.count(uid)).thenReturn(0);
    service.addAddress(uid, addr(false));
    verify(addresses).clearDefault(uid);
    verify(addresses).insert(uid, new Address(null, "Home", "1 Road", "Area", "390001", true), NOW);
  }

  @Test
  void laterAddressIsNotDefaultUnlessAsked() {
    when(addresses.count(uid)).thenReturn(2);
    service.addAddress(uid, addr(false));
    verify(addresses, never()).clearDefault(uid);
    verify(addresses).insert(uid, addr(false), NOW);
  }

  @Test
  void requestedDefaultReplacesOldDefault() {
    when(addresses.count(uid)).thenReturn(2);
    service.addAddress(uid, addr(true));
    verify(addresses).clearDefault(uid);
  }

  @Test
  void updateAddressChecksOwnershipFirst() {
    UUID id = UUID.randomUUID();
    when(addresses.find(uid, id)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.updateAddress(uid, id, addr(true))).isInstanceOf(ApiException.class);
    verify(addresses, never()).update(any(), any(), any());
  }

  @Test
  void updateAddressClearsDefaultOnlyWhenNewDefault() {
    UUID id = UUID.randomUUID();
    Address existing = new Address(id, "H", "l", "a", "390001", false);
    when(addresses.find(uid, id)).thenReturn(Optional.of(existing));
    service.updateAddress(uid, id, addr(false));
    verify(addresses, never()).clearDefault(uid);
    service.updateAddress(uid, id, addr(true));
    verify(addresses).clearDefault(uid);
    verify(addresses, org.mockito.Mockito.times(2)).update(any(), any(), any());
  }

  @Test
  void editingTheDefaultAddressNeverDropsItsDefaultFlag() {
    UUID id = UUID.randomUUID();
    when(addresses.find(uid, id)).thenReturn(Optional.of(new Address(id, "H", "l", "a", "390001", true)));
    service.updateAddress(uid, id, addr(false));
    verify(addresses, never()).clearDefault(uid);
    verify(addresses).update(eq(uid), eq(id), org.mockito.ArgumentMatchers.argThat(a -> a.isDefault()));
  }

  @Test
  void deletingTheDefaultHandsItToTheNextAddress() {
    UUID id = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    when(addresses.find(uid, id)).thenReturn(Optional.of(new Address(id, "H", "l", "a", "390001", true)));
    when(addresses.list(uid)).thenReturn(List.of(new Address(next, "O", "l2", "a2", "390002", false)));
    service.deleteAddress(uid, id);
    verify(addresses).clearDefault(uid);
    verify(addresses).update(eq(uid), eq(next), org.mockito.ArgumentMatchers.argThat(a -> a.isDefault() && a.label().equals("O")));
    UUID other = UUID.randomUUID();
    when(addresses.find(uid, other)).thenReturn(Optional.of(new Address(other, "X", "l", "a", "390001", false)));
    service.deleteAddress(uid, other);
    verify(addresses, org.mockito.Mockito.times(1)).clearDefault(uid);
    when(addresses.find(uid, id)).thenReturn(Optional.of(new Address(id, "H", "l", "a", "390001", true)));
    when(addresses.list(uid)).thenReturn(List.of());
    service.deleteAddress(uid, id);
  }

  @Test
  void addressLookupAndListAndDelete() {
    UUID id = UUID.randomUUID();
    when(addresses.list(uid)).thenReturn(List.of(addr(true)));
    when(addresses.find(uid, id)).thenReturn(Optional.of(addr(true)));
    assertThat(service.addresses(uid)).hasSize(1);
    assertThat(service.address(uid, id)).isNotNull();
    service.deleteAddress(uid, id);
    verify(addresses).delete(uid, id);
  }

  @Test
  void deviceAndAdminHelpersDelegate() {
    UUID admin = UUID.randomUUID();
    when(users.adminIds()).thenReturn(List.of(admin));
    when(devices.tokensOf(uid)).thenReturn(List.of("t1"));
    service.registerDevice(uid, "tok", "ANDROID");
    verify(devices).upsert(uid, "tok", "ANDROID", NOW);
    assertThat(service.adminIds()).containsExactly(admin);
    UUID cashier = UUID.randomUUID();
    when(users.staffIds()).thenReturn(List.of(admin, cashier));
    assertThat(service.staffIds()).containsExactly(admin, cashier);
    assertThat(service.deviceTokens(uid)).containsExactly("t1");
  }

  @Test
  void usersByIdReturnsMap() {
    when(users.findAllByIds(List.of(uid))).thenReturn(List.of(user));
    assertThat(service.users(List.of(uid))).containsOnlyKeys(uid).containsEntry(uid, user);
  }
}
