package com.vijay.mall.wallet.app;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Port the wallet needs from the order module (to show "VSM-1001" next to a coin entry). The order module implements it. */
public interface OrderNumberLookup {
  /**
   * Looks up the display numbers of orders.
   * @param orderIds order ids
   * @return order number by id; unknown ids are absent
   */
  Map<UUID, String> orderNumbers(Collection<UUID> orderIds);
}
