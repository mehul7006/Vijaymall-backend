package com.vijay.mall.identity.infra;

import com.vijay.mall.identity.domain.User;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserRepository {
  private final UserJpa jpa;

  UserRepository(UserJpa jpa) { this.jpa = jpa; }

  private static User toDomain(UserEntity e) { return new User(e.id, e.email, e.name, e.mobile, e.role); }

  public Optional<User> findById(UUID id) { return jpa.findById(id).map(UserRepository::toDomain); }

  public List<User> findAllByIds(java.util.Collection<UUID> ids) {
    return ids.isEmpty() ? List.of() : jpa.findAllById(ids).stream().map(UserRepository::toDomain).toList();
  }

  public Optional<User> findByEmail(String email) { return jpa.findByEmail(email).map(UserRepository::toDomain); }

  @Transactional
  public User insert(String email, String role, Instant now) {
    UserEntity e = new UserEntity();
    e.id = UUID.randomUUID();
    e.email = email;
    e.role = role;
    e.status = "ACTIVE";
    e.createdAt = now;
    e.updatedAt = now;
    return toDomain(jpa.save(e));
  }

  @Transactional
  public void updateProfile(UUID id, String name, String mobile, Instant now) {
    jpa.findById(id).ifPresent(e -> {
      e.name = name;
      // A verified number is only ever replaced by another number, never blanked by a name-only update.
      if (!(e.mobileVerified && mobile == null)) {
        if (!java.util.Objects.equals(e.mobile, mobile)) e.mobileVerified = false;
        e.mobile = mobile;
      }
      e.updatedAt = now;
    });
  }

  /** True when this account's mobile was proven by OTP and {@code newMobile} would change it. */
  public boolean mobileLocked(UUID id, String newMobile) {
    return newMobile != null
        && jpa.findById(id).map(e -> e.mobileVerified && !newMobile.equals(e.mobile)).orElse(false);
  }

  /** The account that proved ownership of this number with an OTP. */
  public Optional<User> findByVerifiedMobile(String mobile) {
    return jpa.findFirstByMobileAndMobileVerifiedTrue(mobile).map(UserRepository::toDomain);
  }

  /**
   * A customer the shop created at the counter with this number can claim it by proving the number with an OTP, so
   * the coins and history earned at the counter show up in the app.
   */
  @Transactional
  public Optional<User> adoptCounterCustomer(String mobile) {
    return jpa.findFirstByMobileAndSourceOrderByCreatedAtAsc(mobile, "POS").map(e -> {
      e.mobileVerified = true;
      return toDomain(e);
    });
  }

  /** A customer who signed up with a verified mobile number and no email. */
  @Transactional
  public User insertMobileCustomer(String mobile, Instant now) {
    UserEntity e = new UserEntity();
    e.id = UUID.randomUUID();
    e.mobile = mobile;
    e.mobileVerified = true;
    e.role = "CUSTOMER";
    e.status = "ACTIVE";
    e.createdAt = now;
    e.updatedAt = now;
    return toDomain(jpa.save(e));
  }

  @Transactional
  public void setRole(UUID id, String role) { jpa.findById(id).ifPresent(e -> e.role = role); }

  /** The account that owns this number: the one that verified it, else the earliest (app or counter) that lists it. */
  public Optional<User> findByMobile(String mobile) {
    // Prefer the account that proved the number, so counter coins go to the person who really owns it.
    return jpa.findFirstByMobileAndMobileVerifiedTrue(mobile)
        .or(() -> jpa.findFirstByMobileOrderByCreatedAtAsc(mobile))
        .map(UserRepository::toDomain);
  }

  /** A customer made at the shop counter; it has no real email, so a placeholder address is used. */
  @Transactional
  public User insertCounterCustomer(String mobile, Instant now) {
    UserEntity e = new UserEntity();
    e.id = UUID.randomUUID();
    e.email = "pos-" + mobile + "@counter.invalid";
    e.mobile = mobile;
    e.role = "CUSTOMER";
    e.status = "ACTIVE";
    e.source = "POS";
    e.createdAt = now;
    e.updatedAt = now;
    return toDomain(jpa.save(e));
  }

  /** Staff (cashier) account, created by an admin. Existing accounts with the email are promoted. */
  @Transactional
  public User upsertStaff(String email, String name, String mobile, String role, Instant now) {
    UserEntity e = jpa.findByEmail(email).orElseGet(() -> {
      UserEntity n = new UserEntity();
      n.id = UUID.randomUUID();
      n.email = email;
      n.status = "ACTIVE";
      n.createdAt = now;
      return n;
    });
    e.role = role;
    if (name != null && !name.isBlank()) e.name = name;
    if (mobile != null && !mobile.isBlank()) e.mobile = mobile;
    e.updatedAt = now;
    return toDomain(jpa.save(e));
  }

  public List<User> byRole(String role) {
    return jpa.findByRoleOrderByCreatedAtDesc(role).stream().map(UserRepository::toDomain).toList();
  }

  @Transactional
  public void setStatus(UUID id, String status) { jpa.findById(id).ifPresent(e -> e.status = status); }

  /** Active admin users, used to address "new order" notifications. */
  public List<UUID> adminIds() { return jpa.activeAdminIds(); }

  /** Active admins and counter staff: everyone who works the orders. */
  public List<UUID> staffIds() { return jpa.activeStaffIds(); }

  public boolean isBlocked(UUID id) { return jpa.existsByIdAndStatus(id, "BLOCKED"); }
}
