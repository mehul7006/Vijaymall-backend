package com.vijay.mall.identity.infra;

import com.vijay.mall.common.Page;
import com.vijay.mall.identity.domain.User;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores user accounts (customers, cashiers and admins) and exposes them as {@link User} records. */
@Repository
public class UserRepository {
  private final UserJpa jpa;

  UserRepository(UserJpa jpa) { this.jpa = jpa; }

  private static User toDomain(UserEntity e) { return new User(e.id, e.email, e.name, e.mobile, e.role); }

  /** The user with this id, if any. */
  public Optional<User> findById(UUID id) { return jpa.findById(id).map(UserRepository::toDomain); }

  /** The users with these ids in one query; unknown ids are skipped. */
  public List<User> findAllByIds(Collection<UUID> ids) {
    return ids.isEmpty() ? List.of() : jpa.findAllById(ids).stream().map(UserRepository::toDomain).toList();
  }

  /** The user with this (already normalised) e-mail, if any. */
  public Optional<User> findByEmail(String email) { return jpa.findByEmail(email).map(UserRepository::toDomain); }

  /** Creates an active user that signs in by e-mail with the given role. */
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

  /**
   * Saves name and mobile. A null name keeps the saved name, a changed mobile loses its verified flag, and a
   * verified mobile (or the only contact of an account without e-mail) is never blanked.
   */
  @Transactional
  public void updateProfile(UUID id, String name, String mobile, Instant now) {
    jpa.findById(id).ifPresent(e -> {
      if (name != null) e.name = name; // a null name leaves the saved one as it is
      // A verified number is only ever replaced by another number, never blanked by a name-only update. An account with no
      // e-mail keeps its number too: blanking it would leave the account with no way to sign in (and break the contact rule).
      if (!((e.mobileVerified || e.email == null) && mobile == null)) {
        if (!Objects.equals(e.mobile, mobile)) e.mobileVerified = false;
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

  /** Changes the role of a user. */
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

  /** Users with this role, newest first. */
  public List<User> byRole(String role) {
    return jpa.findByRoleOrderByCreatedAtDesc(role).stream().map(UserRepository::toDomain).toList();
  }

  /**
   * Strips everything that identifies a customer but keeps the row, so bills and orders (which the shop must keep for
   * tax records) still point at a valid, nameless account that can no longer sign in.
   */
  @Transactional
  public void anonymize(UUID id, Instant now) {
    jpa.findById(id).ifPresent(e -> {
      e.email = "deleted-" + id + "@deleted.invalid";
      e.name = null;
      e.mobile = null;
      e.mobileVerified = false;
      e.status = "BLOCKED";
      e.updatedAt = now;
    });
  }

  /** What the admin sees about a customer beyond the public profile. */
  public record Detail(User user, String status, boolean mobileVerified, String source, Instant createdAt) {}

  /** Admin view of one user, or empty when the id is unknown. */
  public Optional<Detail> detail(UUID id) {
    return jpa.findById(id).map(e -> new Detail(toDomain(e), e.status, e.mobileVerified, e.source, e.createdAt));
  }

  /** Customers (not staff, not deleted accounts), newest first; q matches part of the name, e-mail or mobile number. */
  public Page<Detail> searchCustomers(String q, int page, int size) {
    String text = q == null ? "" : q.strip().toLowerCase();
    var found = jpa.searchCustomers(text, "%" + text.replace("%", "").replace("_", "") + "%", PageRequest.of(page, size));
    return new Page<>(found.getContent().stream()
        .map(e -> new Detail(toDomain(e), e.status, e.mobileVerified, e.source, e.createdAt)).toList(), page, size, found.getTotalElements());
  }

  /** An active admin and the hash of their manager-approval PIN. */
  public record AdminPin(UUID id, String hash) {}

  /** Stores the hash of an admin's manager-approval PIN. */
  @Transactional
  public void setApprovalPin(UUID id, String hash) { jpa.findById(id).ifPresent(e -> e.approvalPinHash = hash); }

  /** Active admins that have a manager-approval PIN, with its hash. */
  public List<AdminPin> adminPins() { return jpa.adminsWithPin().stream().map(e -> new AdminPin(e.id, e.approvalPinHash)).toList(); }

  /** Sets the account status, {@code ACTIVE} or {@code BLOCKED}. */
  @Transactional
  public void setStatus(UUID id, String status) { jpa.findById(id).ifPresent(e -> e.status = status); }

  /** Active admin users, used to address "new order" notifications. */
  public List<UUID> adminIds() { return jpa.activeAdminIds(); }

  /** Active admins and counter staff: everyone who works the orders. */
  public List<UUID> staffIds() { return jpa.activeStaffIds(); }

  /** True when the account is blocked and must not sign in. */
  public boolean isBlocked(UUID id) { return jpa.existsByIdAndStatus(id, "BLOCKED"); }
}
