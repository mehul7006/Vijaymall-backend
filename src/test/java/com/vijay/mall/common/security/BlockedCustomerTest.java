package com.vijay.mall.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.identity.infra.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** A blocked customer is refused within the cache life; staff are never handled here. */
class BlockedCustomerTest {
  private final UserRepository users = mock(UserRepository.class);
  private final UUID id = UUID.randomUUID();

  @Test
  void theDatabaseIsAskedOncePerTenSecondsPerCustomer() {
    var now = new AtomicReference<>(Instant.parse("2027-01-01T00:00:00Z"));
    var clock = new Clock() {
      @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
      @Override public Clock withZone(java.time.ZoneId z) { return this; }
      @Override public Instant instant() { return now.get(); }
    };
    var caches = new AppCaches(clock);
    when(users.isBlocked(id)).thenReturn(false);
    assertThat(SecurityConfig.isBlockedCustomer("CUSTOMER", id.toString(), users, caches)).isFalse();
    assertThat(SecurityConfig.isBlockedCustomer("CUSTOMER", id.toString(), users, caches)).isFalse();
    verify(users, times(1)).isBlocked(id);

    when(users.isBlocked(id)).thenReturn(true);
    now.set(now.get().plusSeconds(11)); // the cached answer has run out
    assertThat(SecurityConfig.isBlockedCustomer("CUSTOMER", id.toString(), users, caches)).isTrue();
    verify(users, times(2)).isBlocked(id);
  }

  @Test
  void evictingTheUserShowsTheChangeAtOnce() {
    var caches = new AppCaches(Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC));
    when(users.isBlocked(id)).thenReturn(false);
    assertThat(SecurityConfig.isBlockedCustomer("CUSTOMER", id.toString(), users, caches)).isFalse();
    when(users.isBlocked(id)).thenReturn(true);
    caches.evict(AppCaches.Name.BLOCKED_USERS, id);
    assertThat(SecurityConfig.isBlockedCustomer("CUSTOMER", id.toString(), users, caches)).isTrue();
  }

  @Test
  void staffAndBadSubjectsAreNotCheckedHere() {
    var caches = new AppCaches(Clock.systemUTC());
    assertThat(SecurityConfig.isBlockedCustomer("ADMIN", id.toString(), users, caches)).isFalse();
    assertThat(SecurityConfig.isBlockedCustomer("CASHIER", id.toString(), users, caches)).isFalse();
    assertThat(SecurityConfig.isBlockedCustomer("CUSTOMER", "not-a-uuid", users, caches)).isFalse();
    verify(users, never()).isBlocked(id);
  }

  @Test
  void theCacheLivesTenSecondsAndHoldsTenThousandCustomers() {
    assertThat(AppCaches.Name.BLOCKED_USERS.ttlSeconds).isEqualTo(10);
    assertThat(AppCaches.Name.BLOCKED_USERS.maxEntries).isEqualTo(10_000);
    assertThat(AppCaches.Name.byKey("blocked-users")).isEqualTo(AppCaches.Name.BLOCKED_USERS);
  }
}
