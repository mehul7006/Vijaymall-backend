package com.vijay.mall.notification.app;

import com.vijay.mall.identity.domain.User;
import java.util.List;

/**
 * Who an admin announcement goes to. Only active accounts that can sign in are ever reached; blocked accounts never are.
 */
public enum Audience {
  /** Every active customer and staff member. */
  ALL(User.CUSTOMER, User.ADMIN, User.CASHIER),
  /** Every active customer. */
  CUSTOMERS(User.CUSTOMER),
  /** Admins and cashiers. */
  STAFF(User.ADMIN, User.CASHIER),
  /** Admins only. */
  ADMINS(User.ADMIN),
  /** Cashiers only. */
  CASHIERS(User.CASHIER),
  /** An explicit list of user ids and mobile numbers. */
  USERS(),
  /** Customers who meet a saved or inline segment (a set of conditions). */
  SEGMENT();

  private final List<String> roles;

  Audience(String... roles) { this.roles = List.of(roles); }

  /** The roles of the people this audience covers; empty for {@link #USERS} and {@link #SEGMENT}, which name people another way. */
  public List<String> roles() { return roles; }

  /** True when the audience is "everyone with one of these roles". */
  public boolean isRoleGroup() { return !roles.isEmpty(); }
}
