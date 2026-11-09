package com.vijay.mall.pos.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.infra.UserRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Manager approval at the counter. Each admin can set a short PIN (kept hashed). When a cashier asks for something above
 * their own limit (a manual discount), a manager types their PIN and the system records which admin approved it.
 */
@Service
public class ApprovalService {
  private final UserRepository users;
  private final RateLimiter limiter;
  private final AuditService audit;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

  /**
   * Creates the service.
   *
   * @param users user store holding the hashed PINs
   * @param limiter rate limiter that slows PIN guessing
   * @param audit audit log
   */
  public ApprovalService(UserRepository users, RateLimiter limiter, AuditService audit) {
    this.users = users;
    this.limiter = limiter;
    this.audit = audit;
  }

  /** The PIN is 4 to 8 digits. Setting it again replaces the old one. */
  public void setPin(UUID adminId, String pin) {
    if (pin == null || !pin.matches("\\d{4,8}")) throw ApiException.validation("pin", "use 4 to 8 digits");
    users.setApprovalPin(adminId, encoder.encode(pin));
    audit.log(adminId, "APPROVAL_PIN_SET", "users", adminId, null);
  }

  /**
   * The admin whose PIN this is, or empty. Guessing is slowed down: a cashier gets 5 tries in 10 minutes (RATE_LIMITED after
   * that), because a 4-digit PIN would otherwise fall to a quick try-them-all.
   */
  public Optional<UUID> approverFor(UUID askedBy, String pin) {
    limiter.check("approval-pin:" + askedBy, 5, 600);
    if (pin == null || pin.isBlank()) return Optional.empty();
    for (UserRepository.AdminPin a : users.adminPins()) {
      if (encoder.matches(pin.trim(), a.hash())) return Optional.of(a.id());
    }
    return Optional.empty();
  }
}
