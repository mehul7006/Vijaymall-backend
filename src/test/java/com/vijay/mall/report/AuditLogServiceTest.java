package com.vijay.mall.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditQuery;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.identity.domain.User;
import com.vijay.mall.identity.infra.UserRepository;
import com.vijay.mall.report.app.AuditLogService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditLogServiceTest {
  private final UUID asha = UUID.randomUUID();
  private final UUID nameless = UUID.randomUUID();
  private final AuditQuery query = mock(AuditQuery.class);
  private final UserRepository users = mock(UserRepository.class);
  private final AuditLogService service = new AuditLogService(query, users);

  @Test
  void rowsCarryTheNameOfThePersonOrTheEmailOrNothingForTheSystem() {
    Instant at = Instant.parse("2026-11-08T10:00:00Z");
    when(query.search(any(), any(), any(), any(), any(), any(), eq(0), eq(20))).thenReturn(new Page<>(List.of(
        new AuditQuery.Entry(UUID.randomUUID(), asha, "UPDATE", "coupon", "c1", "x", at),
        new AuditQuery.Entry(UUID.randomUUID(), nameless, "BLOCK", "staff", "s1", null, at),
        new AuditQuery.Entry(UUID.randomUUID(), null, "EXPIRE", "orders", "o1", null, at),
        new AuditQuery.Entry(UUID.randomUUID(), UUID.randomUUID(), "GONE", "orders", "o2", null, at)), 0, 20, 4));
    when(users.findAllByIds(any())).thenReturn(List.of(new User(asha, "asha@shop.in", "Asha", null, "ADMIN"),
        new User(nameless, "ravi@shop.in", " ", null, "CASHIER")));
    var p = service.search(null, null, null, null, null, null, 0, 20);
    assertThat(p.items()).extracting(AuditLogService.Row::actorName).containsExactly("Asha", "ravi@shop.in", null, null);
    assertThat(p.total()).isEqualTo(4);
  }

  @Test
  void filtersArePassedOnAndBlankOnesAreDropped() {
    when(query.search(any(), any(), any(), any(), any(), any(), eq(0), eq(50))).thenReturn(new Page<>(List.of(), 0, 50, 0));
    service.search(asha, " CREATE ", "  ", "", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), -3, 500);
    verify(query).search(eq(asha), eq("CREATE"), eq(null), eq(null), any(Instant.class), any(Instant.class), eq(0), eq(50));
  }

  @Test
  void backwardsOrTooWideRangesAreRefused() {
    assertThatThrownBy(() -> service.search(null, null, null, null, LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 1), 0, 20))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.search(null, null, null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 1), 0, 20))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void aRangeWithOnlyOneEndIsFineAndTheWidestAllowedRangeIsAccepted() {
    when(query.search(any(), any(), any(), any(), any(), any(), eq(0), eq(20))).thenReturn(new Page<>(List.of(), 0, 20, 0));
    when(users.findAllByIds(any())).thenReturn(List.of());
    service.search(null, null, null, null, LocalDate.of(2026, 11, 1), null, 0, 20);
    service.search(null, null, null, null, null, LocalDate.of(2026, 11, 1), 0, 20);
    // 93 days counting both ends
    service.search(null, null, null, null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 1), 0, 20);
    assertThatThrownBy(() -> service.search(null, null, null, null, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 11, 2), 0, 20))
        .isInstanceOf(ApiException.class);
    // the same day on both ends
    service.search(null, null, null, null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 1), 0, 20);
  }

  @Test
  void anActorWithoutANameIsShownByEmail() {
    when(query.search(any(), any(), any(), any(), any(), any(), eq(0), eq(20))).thenReturn(new Page<>(List.of(
        new AuditQuery.Entry(UUID.randomUUID(), nameless, "BLOCK", "staff", "s1", null, Instant.parse("2026-11-08T10:00:00Z"))), 0, 20, 1));
    when(users.findAllByIds(any())).thenReturn(List.of(new User(nameless, "ravi@shop.in", null, null, "CASHIER")));
    assertThat(service.search(null, null, null, null, null, null, 0, 20).items().get(0).actorName()).isEqualTo("ravi@shop.in");
  }
}
