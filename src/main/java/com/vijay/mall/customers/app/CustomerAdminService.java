package com.vijay.mall.customers.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.RefreshTokenRepository;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.order.app.OrderService;
import com.vijay.mall.order.domain.OrderViews;
import com.vijay.mall.pos.app.CreditService;
import com.vijay.mall.wallet.app.WalletService;
import com.vijay.mall.wallet.infra.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's view of customers: find one, see their standing, switch their account off or on, give or take back coins. */
@Service
public class CustomerAdminService {
  /** active is false for a blocked customer. creditBalancePaise is what they owe the shop (udhar). */
  public record CustomerView(UUID id, String name, String email, String mobile, boolean mobileVerified, boolean active, String source,
                             Instant createdAt, int coinBalance, long creditBalancePaise, long openOrders) {}

  /** One line of the customer list: who they are and whether the account is on. The coins, credit and orders are on the detail view. */
  public record CustomerRow(UUID id, String name, String email, String mobile, boolean active, Instant createdAt) {}

  private final UserRepository users;
  private final WalletService wallet;
  private final CreditService credit;
  private final OrderService orders;
  private final RefreshTokenRepository tokens;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service with the user, wallet, credit and order modules it reads (wired by Spring). */
  public CustomerAdminService(UserRepository users, WalletService wallet, CreditService credit, OrderService orders,
                              RefreshTokenRepository tokens, AuditService audit, Clock clock) {
    this.users = users;
    this.wallet = wallet;
    this.credit = credit;
    this.orders = orders;
    this.tokens = tokens;
    this.audit = audit;
    this.clock = clock;
  }

  private CustomerView view(UserRepository.Detail d) {
    User u = d.user();
    return new CustomerView(u.id(), u.name(), u.email(), u.mobile(), d.mobileVerified(), !"BLOCKED".equals(d.status()), d.source(),
        d.createdAt(), wallet.balance(u.id()), credit.account(u.id()).balancePaise(), orders.openOrderCount(u.id()));
  }

  private UserRepository.Detail customer(UUID id) {
    return users.detail(id).filter(d -> User.CUSTOMER.equals(d.user().role())).orElseThrow(() -> ApiException.notFound("Customer"));
  }

  /** A light list (no per-customer lookups), so a page of 50 is one query, not hundreds. Open one customer for the full picture. */
  @Transactional(readOnly = true)
  public Page<CustomerRow> search(String q, int page, int size) {
    Page<UserRepository.Detail> p = users.searchCustomers(q, Math.max(0, page), Page.clampSize(size));
    return p.withItems(p.items().stream().map(d -> new CustomerRow(d.user().id(), d.user().name(), d.user().email(), d.user().mobile(),
        !"BLOCKED".equals(d.status()), d.createdAt())).toList());
  }

  /**
   * The full admin picture of one customer: contact details, coins, credit owed and open orders.
   * @throws com.vijay.mall.common.error.ApiException 404 when the id is unknown or is not a customer
   */
  @Transactional(readOnly = true)
  public CustomerView get(UUID id) { return view(customer(id)); }

  /** Blocking also signs the customer out of every device; they cannot get a new sign-in until unblocked. */
  @Transactional
  public CustomerView setActive(UUID id, boolean active, UUID actor) {
    customer(id);
    users.setStatus(id, active ? "ACTIVE" : "BLOCKED");
    if (!active) tokens.revokeAll(id, clock.instant());
    audit.log(actor, active ? "CUSTOMER_UNBLOCK" : "CUSTOMER_BLOCK", "users", id, null);
    return view(customer(id));
  }

  /** Gives (positive) or takes back (negative) coins, with a reason that is kept in the audit trail. */
  @Transactional
  public CustomerView adjustCoins(UUID id, int delta, String reason, UUID actor) {
    customer(id);
    String why = reason == null ? "" : reason.strip();
    if (why.isEmpty() || why.length() > 100) throw ApiException.validation("reason", "1 to 100 characters");
    try {
      wallet.adminAdjust(id, delta, actor, why);
    } catch (ApiException e) {
      throw withDeltaField(e);
    }
    return view(customer(id));
  }

  /** The wallet reports its coin errors under "coins"; the request field is "delta", so both keys carry the message. */
  private static ApiException withDeltaField(ApiException e) {
    Object fields = e.details().get("fields");
    if (!(fields instanceof Map<?, ?> f) || !f.containsKey("coins")) return e;
    String message = String.valueOf(f.get("coins"));
    return new ApiException(e.status(), e.code(), e.getMessage(), Map.of("fields", Map.of("delta", message, "coins", message)));
  }

  /** The customer's coin history, newest first (size clamped to 1..50). 404 for an unknown id or a staff account. */
  @Transactional(readOnly = true)
  public Page<WalletRepository.LedgerRow> walletLedger(UUID id, int page, int size) {
    customer(id);
    return wallet.ledger(id, page, size);
  }

  /** The customer's orders, newest first, as the same rows the admin order list shows (size clamped to 1..50). */
  @Transactional(readOnly = true)
  public Page<OrderViews.AdminSummary> orders(UUID id, int page, int size) {
    User u = customer(id).user();
    Page<OrderViews.Summary> p = orders.list(id, page, size);
    return p.withItems(p.items().stream().map(r -> new OrderViews.AdminSummary(r.id(), r.orderNo(), r.status(), r.fulfilment(),
        r.totalPaise(), r.itemCount(), r.placedAt(), u.name(), u.email(), u.mobile(), r.subtotalPaise(), r.couponCode(),
        r.couponDiscountPaise(), r.coinsUsed(), r.coinDiscountPaise(), r.deliveryFeePaise(), r.paymentMethod(),
        r.paymentStatus())).toList());
  }
}
