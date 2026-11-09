package com.vijay.mall.pos.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counter staff accounts. A cashier signs in with the normal email code flow. */
@Service
public class StaffService {
  private final UserRepository users;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public StaffService(UserRepository users, AuditService audit, Clock clock) {
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  /** All cashier accounts. */
  public List<User> list() { return users.byRole(User.CASHIER); }

  /** False for a blocked cashier. */
  public boolean isActive(UUID id) { return !users.isBlocked(id); }

  /**
   * Creates a cashier account for a new e-mail. An e-mail that already has an account is refused unless the caller confirms the
   * promotion and the account is an active customer.
   *
   * @param confirmPromote true when the admin has agreed to turn an existing active customer into a cashier (their name and
   *                       mobile are kept unless the request gives new ones)
   * @throws ApiException 400 for an invalid email or an email that belongs to an admin; 409 {@code EMAIL_ALREADY_REGISTERED}
   *                      for any other existing account unless {@code confirmPromote} is true and the account is an active
   *                      customer (a blocked, deleted, cashier or admin account is never promoted)
   */
  @Transactional
  public User create(String email, String name, String mobile, boolean confirmPromote, UUID actor) {
    String e = email == null ? "" : email.trim().toLowerCase();
    if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.validation("email", "enter a valid email");
    var existing = users.findByEmail(e);
    if (existing.isPresent()) {
      User u = existing.get();
      if (User.ADMIN.equals(u.role())) throw ApiException.validation("email", "this email belongs to an admin");
      boolean promotable = confirmPromote && User.CUSTOMER.equals(u.role())
          && users.detail(u.id()).map(d -> "ACTIVE".equals(d.status())).orElse(false);
      if (!promotable) {
        throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "This email already has an account", Map.of("role", u.role()));
      }
    }
    User u = users.upsertStaff(e, name, mobile, User.CASHIER, clock.instant());
    audit.log(actor, existing.isPresent() ? "PROMOTE" : "CREATE", "staff", u.id(), e);
    return u;
  }

  /** Blocks or unblocks a cashier. Blocked accounts cannot sign in or refresh tokens. */
  @Transactional
  public void setActive(UUID id, boolean active, UUID actor) {
    User u = users.findById(id).filter(x -> User.CASHIER.equals(x.role())).orElseThrow(() -> ApiException.notFound("Cashier"));
    users.setStatus(u.id(), active ? "ACTIVE" : "BLOCKED");
    audit.log(actor, active ? "UNBLOCK" : "BLOCK", "staff", id, u.email());
  }
}
