package com.vijay.mall.identity.domain;

import java.util.UUID;

/**
 * A saved delivery address of a customer.
 * @param id the address id; null before it is saved
 * @param label short name such as Home or Office
 * @param line1 house number and street
 * @param area locality or area
 * @param pincode postal code
 * @param isDefault true for the customer's default address (at most one per customer)
 * @param phone contact number for the delivery (10 digits), or null when none was given; old order snapshots have none
 */
public record Address(UUID id, String label, String line1, String area, String pincode, boolean isDefault, String phone) {
  /** An address without a contact phone. */
  public Address(UUID id, String label, String line1, String area, String pincode, boolean isDefault) {
    this(id, label, line1, area, pincode, isDefault, null);
  }
}
