package com.vijay.mall.identity.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.AddressRepository;
import com.vijay.mall.identity.infra.DeviceRepository;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.pos.app.CreditService;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer deleting their own account (required by the app stores and by data-protection law). Personal details,
 * addresses, devices and sign-ins are erased; bills and orders stay, nameless, because the shop must keep them for tax.
 * Not allowed while an order is open or the customer still owes the shop money. Coins are forfeited.
 */
@Service
public class AccountDeletionService {
  private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

  private final UserRepository users;
  private final AddressRepository addresses;
  private final DeviceRepository devices;
  private final RefreshTokenRepository tokens;
  private final OrderService orders;
  private final CreditService credit;
  private final AuditService audit;
  private final Clock clock;

  /**
   * Creates the service with the repositories and the order and credit modules it checks before erasing (wired by Spring).
   */
  public AccountDeletionService(UserRepository users, AddressRepository addresses, DeviceRepository devices,
                                RefreshTokenRepository tokens, OrderService orders, CreditService credit, AuditService audit,
                                Clock clock) {
    this.users = users;
    this.addresses = addresses;
    this.devices = devices;
    this.tokens = tokens;
    this.orders = orders;
    this.credit = credit;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Erases the customer's personal data after checking that deleting is allowed.
   * <p>
   * Fails with 403 for staff accounts, 409 {@code ACCOUNT_HAS_OPEN_ORDERS} while an order is still open and 409
   * {@code ACCOUNT_HAS_DUES} while the customer owes the shop credit. On success addresses and devices are removed, every
   * refresh token is revoked and the user row is anonymised.
   * @param userId the customer to delete
   */
  @Transactional
  public void deleteAccount(UUID userId) {
    User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    if (!User.CUSTOMER.equals(u.role())) {
      throw ApiException.forbidden(); // staff accounts are removed by an admin, not from the customer app
    }
    long open = orders.openOrderCount(userId);
    if (open > 0) {
      throw ApiException.conflict("ACCOUNT_HAS_OPEN_ORDERS", "Finish or cancel your open orders first", Map.of("openOrders", open));
    }
    long owed = credit.account(userId).balancePaise();
    if (owed > 0) {
      throw ApiException.conflict("ACCOUNT_HAS_DUES", "Clear your credit balance at the shop first", Map.of("owedPaise", owed));
    }
    var now = clock.instant();
    addresses.deleteAll(userId);
    devices.deleteAll(userId);
    tokens.revokeAll(userId, now);
    users.anonymize(userId, now);
    audit.log(userId, "ACCOUNT_DELETED", "users", userId, null);
    log.info("Account deleted user={}", userId);
  }
}
