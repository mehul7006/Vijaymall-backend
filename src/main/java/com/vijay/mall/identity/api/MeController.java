package com.vijay.mall.identity.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.identity.app.AccountDeletionService;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.identity.domain.Address;
import com.vijay.mall.identity.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
  public record ProfileInput(String name, String mobile) {}
  public record AddressInput(@NotBlank String label, @NotBlank String line1, @NotBlank String area,
                             @NotBlank String pincode, Boolean isDefault) {
    Address toAddress() { return new Address(null, label, line1, area, pincode, Boolean.TRUE.equals(isDefault)); }
  }
  public record DeviceInput(@NotBlank String fcmToken, @NotBlank String platform) {}

  private final ProfileService profile;
  private final AccountDeletionService deletion;

  public MeController(ProfileService profile, AccountDeletionService deletion) {
    this.profile = profile;
    this.deletion = deletion;
  }

  /** Erases the signed-in customer's personal data. Refused while orders are open or credit is owed. */
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteAccount() { deletion.deleteAccount(CurrentUser.id()); }

  @GetMapping
  public User me() { return profile.get(CurrentUser.id()); }

  @PutMapping
  public User update(@RequestBody ProfileInput in) { return profile.update(CurrentUser.id(), in.name(), in.mobile()); }

  @GetMapping("/addresses")
  public List<Address> addresses() { return profile.addresses(CurrentUser.id()); }

  @PostMapping("/addresses")
  @ResponseStatus(HttpStatus.CREATED)
  public Address add(@Valid @RequestBody AddressInput in) { return profile.addAddress(CurrentUser.id(), in.toAddress()); }

  @PutMapping("/addresses/{id}")
  public Address edit(@PathVariable UUID id, @Valid @RequestBody AddressInput in) {
    return profile.updateAddress(CurrentUser.id(), id, in.toAddress());
  }

  @DeleteMapping("/addresses/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { profile.deleteAddress(CurrentUser.id(), id); }

  @PostMapping("/devices")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void device(@Valid @RequestBody DeviceInput in) {
    profile.registerDevice(CurrentUser.id(), in.fcmToken(), in.platform());
  }
}
