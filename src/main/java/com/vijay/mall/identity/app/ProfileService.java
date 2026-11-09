package com.vijay.mall.identity.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.AddressRepository;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile, addresses and device tokens. Also the public face of identity for other modules. */
@Service
public class ProfileService {
  private final UserRepository users;
  private final AddressRepository addresses;
  private final DeviceRepository devices;
  private final Clock clock;

  public ProfileService(UserRepository users, AddressRepository addresses, DeviceRepository devices, Clock clock) {
    this.users = users;
    this.addresses = addresses;
    this.devices = devices;
    this.clock = clock;
  }

  public User get(UUID id) { return users.findById(id).orElseThrow(() -> ApiException.notFound("User")); }

  @Transactional
  public User update(UUID id, String name, String mobile) {
    if (mobile != null && !mobile.isBlank() && !mobile.matches("\\d{10}")) {
      throw ApiException.validation("mobile", "must be 10 digits");
    }
    String newMobile = (mobile == null || mobile.isBlank()) ? null : mobile;
    if (users.mobileLocked(id, newMobile)) {
      throw ApiException.conflict("MOBILE_VERIFIED", "A verified mobile number cannot be changed here", Map.of());
    }
    // a missing name means "leave it as it is": the repository keeps the saved name when it is given null
    users.updateProfile(id, name == null ? null : name.trim(), newMobile, clock.instant());
    return get(id);
  }

  public List<Address> addresses(UUID userId) { return addresses.list(userId); }

  public Address address(UUID userId, UUID id) {
    return addresses.find(userId, id).orElseThrow(() -> ApiException.notFound("Address"));
  }

  @Transactional
  public Address addAddress(UUID userId, Address in) {
    boolean makeDefault = in.isDefault() || addresses.count(userId) == 0;
    if (makeDefault) addresses.clearDefault(userId);
    return addresses.insert(userId, new Address(null, in.label(), in.line1(), in.area(), in.pincode(), makeDefault), clock.instant());
  }

  @Transactional
  public Address updateAddress(UUID userId, UUID id, Address in) {
    Address current = address(userId, id);
    // The default only ever moves by marking another address as the default: leaving the flag out, or sending false on the
    // current default, keeps it, so a customer who has addresses always has exactly one default.
    if (in.isDefault()) addresses.clearDefault(userId);
    addresses.update(userId, id, new Address(null, in.label(), in.line1(), in.area(), in.pincode(), current.isDefault() || in.isDefault()));
    return address(userId, id);
  }

  @Transactional
  public void deleteAddress(UUID userId, UUID id) {
    boolean wasDefault = addresses.find(userId, id).map(Address::isDefault).orElse(false);
    addresses.delete(userId, id);
    if (wasDefault) {
      // the customer always keeps a default address while they have any: the first one left takes over
      addresses.list(userId).stream().findFirst().ifPresent(a -> {
        addresses.clearDefault(userId);
        addresses.update(userId, a.id(), new Address(a.id(), a.label(), a.line1(), a.area(), a.pincode(), true));
      });
    }
  }

  @Transactional
  public void registerDevice(UUID userId, String token, String platform) {
    devices.upsert(userId, token, platform, clock.instant());
  }

  public List<UUID> adminIds() { return users.adminIds(); }

  /** Active admins and counter staff: everyone who works the orders. */
  public List<UUID> staffIds() { return users.staffIds(); }

  public List<String> deviceTokens(UUID userId) { return devices.tokensOf(userId); }

  /** Many users at once (for admin order lists). */
  public java.util.Map<UUID, User> users(java.util.Collection<UUID> ids) {
    java.util.Map<UUID, User> out = new java.util.HashMap<>();
    users.findAllByIds(ids).forEach(u -> out.put(u.id(), u));
    return out;
  }
}
