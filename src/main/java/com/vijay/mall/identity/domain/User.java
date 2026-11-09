package com.vijay.mall.identity.domain;

import java.util.UUID;

/**
 * An account holder as the rest of the app sees them.
 * @param id the user id
 * @param email sign-in e-mail, or null for an account that signs in only by mobile
 * @param name display name, may be null
 * @param mobile 10-digit mobile number, may be null
 * @param role one of {@link #CUSTOMER}, {@link #ADMIN} or {@link #CASHIER}
 */
public record User(UUID id, String email, String name, String mobile, String role) {
  /** Role of a shopper. */
  public static final String CUSTOMER = "CUSTOMER";
  /** Role of the shop owner or manager: full access to the admin screens. */
  public static final String ADMIN = "ADMIN";
  /** Role of counter staff: may work orders and the point of sale, but not the other admin screens. */
  public static final String CASHIER = "CASHIER";
}
