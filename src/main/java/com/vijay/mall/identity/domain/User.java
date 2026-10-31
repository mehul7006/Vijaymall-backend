package com.vijay.mall.identity.domain;

import java.util.UUID;

public record User(UUID id, String email, String name, String mobile, String role) {
  public static final String CUSTOMER = "CUSTOMER";
  public static final String ADMIN = "ADMIN";
  public static final String CASHIER = "CASHIER";
}
