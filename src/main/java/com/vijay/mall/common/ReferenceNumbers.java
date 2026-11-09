package com.vijay.mall.common;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What screens call to show "VSM-1001" instead of an internal id. The sources (order, counter) are looked up when
 * asked, not when this bean is built, so the catalog and wallet can use it without creating a startup cycle with the
 * order module.
 */
@Component
public class ReferenceNumbers {
  private final Supplier<List<ReferenceLookup>> sources;

  /** Creates the service for Spring; the sources are looked up on each call. */
  @Autowired
  public ReferenceNumbers(ObjectProvider<ReferenceLookup> providers) {
    this.sources = () -> providers.orderedStream().toList();
  }

  /** For tests and for code that already holds the sources. */
  public ReferenceNumbers(List<ReferenceLookup> fixed) { this.sources = () -> fixed; }

  /** Number per id; an id nobody knows is left out. */
  public Map<UUID, String> of(Collection<UUID> ids) { return ReferenceLookup.resolve(sources.get(), ids); }
}
