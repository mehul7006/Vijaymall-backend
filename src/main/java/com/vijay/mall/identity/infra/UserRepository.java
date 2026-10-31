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
      e.mobile = mobile;
      e.updatedAt = now;
    });
  }

  @Transactional
  public void setRole(UUID id, String role) { jpa.findById(id).ifPresent(e -> e.role = role); }

  /** Earliest account with this mobile number, whether it signed up in the app or was made at the counter. */
  public Optional<User> findByMobile(String mobile) {
    return jpa.findFirstByMobileOrderByCreatedAtAsc(mobile).map(UserRepository::toDomain);
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

  public boolean isBlocked(UUID id) { return jpa.existsByIdAndStatus(id, "BLOCKED"); }
}
