package com.vijay.mall.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BaseEntityTest {
  private static class Thing extends BaseEntity {}

  @Test
  void aNewObjectIsNewUntilItIsPersistedOrLoaded() {
    var t = new Thing();
    t.id = UUID.randomUUID();
    assertThat(t.getId()).isEqualTo(t.id);
    assertThat(t.isNew()).as("so save() issues a plain INSERT").isTrue();
    t.markPersisted();
    assertThat(t.isNew()).as("after insert or load, save() must UPDATE").isFalse();
  }
}
