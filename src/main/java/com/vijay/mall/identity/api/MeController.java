package com.vijay.mall.identity.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.identity.app.AccountDeletionService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own account under {@code /api/v1/me}: profile, saved delivery addresses, push device
 * registration and account deletion. Any signed-in user may call it, and it only ever touches the caller's own data.
 */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {
  /**
   * Request body to change the profile.
   * @param name new display name, or null to keep the saved one
   * @param mobile new 10-digit mobile number; null or blank clears it unless it was verified by OTP
   */
  /**
   * The editable profile. A missing {@code name} keeps the saved one; {@code mobile} is blank (no mobile) or exactly 10 digits.
   *
   * @param name the display name, or null to keep it
   * @param mobile blank, or 10 digits without country code
   */
  public record ProfileInput(String name, @Pattern(regexp = "^$|\\d{10}", message = "must be 10 digits") String mobile) {}
  /**
   * Request body for adding or editing a delivery address.
   * @param label short name such as Home or Office
   * @param line1 house number and street
   * @param area locality or area
   * @param pincode postal code, exactly 6 digits
   * @param isDefault true to make this the default address; null or false leaves the default unchanged
   * @param phone optional contact phone for the delivery: blank, or exactly 10 digits
   */
  public record AddressInput(@NotBlank String label, @NotBlank String line1, @NotBlank String area,
                             @NotBlank @Pattern(regexp = "^$|\\d{6}", message = "must be 6 digits") String pincode, Boolean isDefault,
                             @Pattern(regexp = "^$|\\d{10}", message = "must be 10 digits") String phone) {
    /** An address input without a phone. */
    public AddressInput(String label, String line1, String area, String pincode, Boolean isDefault) {
      this(label, line1, area, pincode, isDefault, null);
    }

    Address toAddress() {
      return new Address(null, label, line1, area, pincode, Boolean.TRUE.equals(isDefault), phone == null || phone.isBlank() ? null : phone);
    }
  }
  /**
   * Request body to register a phone for push notifications.
   * @param fcmToken the Firebase Cloud Messaging token of the device
   * @param platform the device platform, for example {@code android}
   */
  public record DeviceInput(@NotBlank String fcmToken, @NotBlank String platform) {}

  private final ProfileService profile;
  private final AccountDeletionService deletion;

  /** Creates the controller with the profile and account-deletion services (wired by Spring). */
  public MeController(ProfileService profile, AccountDeletionService deletion) {
    this.profile = profile;
    this.deletion = deletion;
  }

  /** Erases the signed-in customer's personal data. Refused while orders are open or credit is owed. */
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAccount() { deletion.deleteAccount(CurrentUser.id()); }

  /** Returns the signed-in user's profile. */
  @GetMapping
  public User me() { return profile.get(CurrentUser.id()); }

  /**
   * Changes the display name and mobile number; answers with the saved profile.
   * <p>
   * Fails with 422 when the mobile is not 10 digits and with 409 {@code MOBILE_VERIFIED} when the verified number would
   * be replaced.
   */
  @PutMapping
  public User update(@Valid @RequestBody ProfileInput in) { return profile.update(CurrentUser.id(), in.name(), in.mobile()); }

  /** Lists the customer's saved addresses, default address first. */
  @GetMapping("/addresses")
  public List<Address> addresses() { return profile.addresses(CurrentUser.id()); }

  /** Adds a delivery address (201). The first address, or one marked as default, becomes the default. */
  @PostMapping("/addresses")
  @ResponseStatus(HttpStatus.CREATED)
  public Address add(@Valid @RequestBody AddressInput in) { return profile.addAddress(CurrentUser.id(), in.toAddress()); }

  /**
   * Changes one of the customer's addresses. The default only moves when another address is marked as default.
   * <p>
   * Fails with 404 when the address does not exist or belongs to someone else.
   */
  @PutMapping("/addresses/{id}")
  public Address edit(@PathVariable UUID id, @Valid @RequestBody AddressInput in) {
    return profile.updateAddress(CurrentUser.id(), id, in.toAddress());
  }

  /** Deletes one of the customer's addresses; if it was the default, another one takes over. */
  @DeleteMapping("/addresses/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { profile.deleteAddress(CurrentUser.id(), id); }

  /**
   * Forgets this phone's push token when the customer signs out (204). Safe to repeat: an unknown token, or one registered to
   * someone else, also answers 204 and changes nothing. The token goes in the path, URL-encoded.
   */
  @DeleteMapping("/devices/{fcmToken}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void unregisterDevice(@PathVariable String fcmToken) { profile.unregisterDevice(CurrentUser.id(), fcmToken); }

  /** Registers or re-assigns the device's push token to the signed-in customer so notifications reach this phone. */
  @PostMapping("/devices")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void device(@Valid @RequestBody DeviceInput in) {
    profile.registerDevice(CurrentUser.id(), in.fcmToken(), in.platform());
  }
}
