package com.vijay.mall.identity.app;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.AddressRepository;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
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

  /** Creates the service with the user, address and device repositories (wired by Spring). */
  public ProfileService(UserRepository users, AddressRepository addresses, DeviceRepository devices, Clock clock) {
    this.users = users;
    this.addresses = addresses;
    this.devices = devices;
    this.clock = clock;
  }

  /**
   * Returns a user by id.
   * @throws com.vijay.mall.common.error.ApiException 404 when the user does not exist
   */
  public User get(UUID id) { return users.findById(id).orElseThrow(() -> ApiException.notFound("User")); }

  /**
   * Saves the display name and mobile number.
   * <p>
   * The mobile must be 10 digits (or empty to clear it) and a number proven by OTP cannot be replaced here (409
   * {@code MOBILE_VERIFIED}). A null name leaves the saved name unchanged.
   * @return the saved profile
   */
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

  /** Lists the customer's addresses, default first, then oldest first. */
  public List<Address> addresses(UUID userId) { return addresses.list(userId); }

  /**
   * Returns one of the customer's addresses.
   * @throws com.vijay.mall.common.error.ApiException 404 when it does not exist or belongs to someone else
   */
  public Address address(UUID userId, UUID id) {
    return addresses.find(userId, id).orElseThrow(() -> ApiException.notFound("Address"));
  }

  /** Adds an address. It becomes the default when it is marked so or when it is the customer's first one. */
  @Transactional
  public Address addAddress(UUID userId, Address in) {
    boolean makeDefault = in.isDefault() || addresses.count(userId) == 0;
    if (makeDefault) addresses.clearDefault(userId);
    return addresses.insert(userId, new Address(null, in.label(), in.line1(), in.area(), in.pincode(), makeDefault), clock.instant());
  }

  /**
   * Edits an address. The default only moves by marking another address as default, so a customer who has
   * addresses always has exactly one default.
   */
  @Transactional
  public Address updateAddress(UUID userId, UUID id, Address in) {
    Address current = address(userId, id);
    // The default only ever moves by marking another address as the default: leaving the flag out, or sending false on the
    // current default, keeps it, so a customer who has addresses always has exactly one default.
    if (in.isDefault()) addresses.clearDefault(userId);
    addresses.update(userId, id, new Address(null, in.label(), in.line1(), in.area(), in.pincode(), current.isDefault() || in.isDefault()));
    return address(userId, id);
  }

  /** Deletes an address; when it was the default, the first remaining address becomes the default. */
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

  /** Stores a push token for the user; a token that was registered by someone else is re-assigned to this user. */
  @Transactional
  public void registerDevice(UUID userId, String token, String platform) {
    devices.upsert(userId, token, platform, clock.instant());
  }

  /** Ids of the active admins, for example to address "new order" notifications. */
  public List<UUID> adminIds() { return users.adminIds(); }

  /** Active admins and counter staff: everyone who works the orders. */
  public List<UUID> staffIds() { return users.staffIds(); }

  /** Push tokens of all the user's registered devices. */
  public List<String> deviceTokens(UUID userId) { return devices.tokensOf(userId); }

  /** Many users at once (for admin order lists). */
  public Map<UUID, User> users(Collection<UUID> ids) {
    Map<UUID, User> out = new HashMap<>();
    users.findAllByIds(ids).forEach(u -> out.put(u.id(), u));
    return out;
  }
}
