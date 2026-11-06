package com.vijay.mall.common;

/**
 * Port for who the shop is. The shop settings the admin edits are the only source of the shop's name, phone, address
 * and number prefix; any module that needs one of them (order numbers, sign-in e-mails, payment pages) asks here
 * instead of keeping its own copy. The pos module implements it.
 */
public interface ShopIdentity {
  /** details is the longer "about the shop" text; orderPrefix starts every order and bill number. */
  record Info(String name, String phone, String address, String promise, String details, String orderPrefix) {}

  Info identity();
}
