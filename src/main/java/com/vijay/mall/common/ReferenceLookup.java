package com.vijay.mall.common;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Port for turning an internal id (an order, a counter bill, a return) into the number people read and say aloud
 * (VSM-1001, VSM/2026-27/000042). Every module that shows such a reference asks this instead of printing the id, so
 * the customer app, the admin app and the counter always show the same number. Modules that own documents implement it.
 */
public interface ReferenceLookup {
  /** Numbers for the ids this source knows; other ids are simply left out. */
  Map<UUID, String> numbers(Collection<UUID> ids);

  /** Asks every source and merges the answers. */
  static Map<UUID, String> resolve(Collection<? extends ReferenceLookup> sources, Collection<UUID> ids) {
    Map<UUID, String> out = new HashMap<>();
    if (ids.isEmpty()) return out;
    for (ReferenceLookup s : sources) out.putAll(s.numbers(ids));
    return out;
  }
}
