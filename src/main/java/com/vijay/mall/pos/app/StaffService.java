package com.vijay.mall.pos.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Counter staff accounts. A cashier signs in with the normal email code flow. */
@Service
public class StaffService {
  private final UserRepository users;
  private final AuditService audit;
  private final Clock clock;

  public StaffService(UserRepository users, AuditService audit, Clock clock) {
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  public List<User> list() { return users.byRole(User.CASHIER); }

  /** False for a blocked cashier. */
  public boolean isActive(UUID id) { return !users.isBlocked(id); }

  @Transactional
  public User create(String email, String name, String mobile, UUID actor) {
    String e = email == null ? "" : email.trim().toLowerCase();
    if (!e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.validation("email", "enter a valid email");
    users.findByEmail(e).filter(u -> User.ADMIN.equals(u.role()))
        .ifPresent(u -> { throw ApiException.validation("email", "this email belongs to an admin"); });
    User u = users.upsertStaff(e, name, mobile, User.CASHIER, clock.instant());
    users.setStatus(u.id(), "ACTIVE");
    audit.log(actor, "CREATE", "staff", u.id(), e);
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
