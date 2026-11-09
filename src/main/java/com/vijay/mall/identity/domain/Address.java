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
 */
public record Address(UUID id, String label, String line1, String area, String pincode, boolean isDefault) {}
