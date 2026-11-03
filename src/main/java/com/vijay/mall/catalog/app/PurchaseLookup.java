package com.vijay.mall.catalog.app;

import java.util.Optional;
import java.util.UUID;

/** Lets the catalog ask the order module whether a customer has received a product, without depending on it. */
public interface PurchaseLookup {
  /** The customer's latest delivered or picked-up order that contains the product. */
  Optional<UUID> deliveredOrderWith(UUID userId, UUID productId);
}
