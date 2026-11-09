package com.vijay.mall.pos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.OfflineExceptionKind;
import com.vijay.mall.pos.domain.OfflineFinding;
import com.vijay.mall.pos.infra.OfflineExceptionRepository.Row;
import com.vijay.mall.pos.infra.OfflineExceptionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OfflineExceptionServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
  private final UUID sale = UUID.randomUUID();
  private final UUID admin = UUID.randomUUID();
  private OfflineExceptionRepository repo;
  private AuditService audit;
  private SimpleMeterRegistry registry;
  private OfflineExceptionService service;

  @BeforeEach
  void setUp() {
    repo = mock(OfflineExceptionRepository.class);
    audit = mock(AuditService.class);
    registry = new SimpleMeterRegistry();
    service = new OfflineExceptionService(repo, audit, registry, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private Row row(Instant reviewedAt) {
    return new Row(UUID.randomUUID(), sale, "VSM/2026-27/000001", admin, "Cashier", NOW, OfflineExceptionKind.PRICE_CHANGED, "d", null, null, "1", "2",
        NOW, null, reviewedAt, null);
  }

  @Test
  void findingsAreStoredAndCountedByKind() {
    OfflineFinding a = new OfflineFinding(OfflineExceptionKind.PRICE_CHANGED, "p", UUID.randomUUID(), "1", "2");
    OfflineFinding b = new OfflineFinding(OfflineExceptionKind.OVERSOLD, "o", UUID.randomUUID(), "1", "3");
    service.record(sale, List.of(a, b, a));
    verify(repo).insert(sale, b, NOW);
    assertThat(registry.get("mall.pos.offline.exceptions.recorded").tag("kind", "PRICE_CHANGED").counter().count()).isEqualTo(2);
    assertThat(registry.get("mall.pos.offline.exceptions.recorded").tag("kind", "OVERSOLD").counter().count()).isEqualTo(1);
  }

  @Test
  void nothingIsStoredForNoFindings() {
    service.record(sale, List.of());
    verify(repo, never()).insert(any(), any(), any());
  }

  @Test
  void theFindingsOfABillAreReadBack() {
    OfflineFinding a = new OfflineFinding(OfflineExceptionKind.DISCOUNT, "d", null, "0", "5");
    when(repo.forSale(sale)).thenReturn(List.of(a));
    assertThat(service.forSale(sale)).containsExactly(a);
  }

  @Test
  void listingDefaultsToOpenAcceptsReviewedAndRefusesAnythingElse() {
    when(repo.page(false, 0, 20)).thenReturn(List.of(row(null)));
    when(repo.count(false)).thenReturn(1L);
    var open = service.list(null, 0, 20);
    assertThat(open.items()).hasSize(1);
    assertThat(open.total()).isEqualTo(1);
    assertThat(service.list(" ", -3, 20).page()).isZero();
    when(repo.page(true, 1, 50)).thenReturn(List.of());
    when(repo.count(true)).thenReturn(7L);
    var reviewed = service.list("reviewed", 1, 500);
    assertThat(reviewed.size()).isEqualTo(50);
    assertThat(reviewed.total()).isEqualTo(7);
    assertThat(service.list("open", 0, 20).items()).hasSize(1);
    assertThatThrownBy(() -> service.list("DONE", 0, 20)).isInstanceOf(ApiException.class);
  }

  @Test
  void aFirstReviewIsRecordedAndAudited() {
    UUID id = UUID.randomUUID();
    when(repo.review(id, admin, "checked", NOW)).thenReturn(true);
    when(repo.find(id)).thenReturn(Optional.of(row(NOW)));
    Row r = service.review(id, "  checked ", admin);
    assertThat(r.reviewedAt()).isEqualTo(NOW);
    verify(audit).log(eq(admin), eq("OFFLINE_EXCEPTION_REVIEWED"), eq("pos_sale"), eq(sale), anyString());
  }

  @Test
  void aReviewNoteIsOptionalButLimited() {
    UUID id = UUID.randomUUID();
    when(repo.review(id, admin, null, NOW)).thenReturn(true);
    when(repo.find(id)).thenReturn(Optional.of(row(NOW)));
    service.review(id, " ", admin);
    service.review(id, null, admin);
    verify(repo, Mockito.times(2)).review(id, admin, null, NOW);
    assertThatThrownBy(() -> service.review(id, "x".repeat(501), admin)).isInstanceOf(ApiException.class);
  }

  @Test
  void aSecondReviewIsAConflictAndAnUnknownIdIsNotFound() {
    UUID id = UUID.randomUUID();
    when(repo.find(id)).thenReturn(Optional.of(row(NOW)));
    assertThatThrownBy(() -> service.review(id, "again", admin)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("ALREADY_REVIEWED");
    UUID unknown = UUID.randomUUID();
    when(repo.find(unknown)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.review(unknown, null, admin)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("NOT_FOUND");
    verify(audit, never()).log(any(), eq("OFFLINE_EXCEPTION_REVIEWED"), anyString(), any(), anyString());
  }

  @Test
  void theOpenAndStaleCountsComeFromTheRepository() {
    when(repo.openCount()).thenReturn(4L);
    when(repo.openOlderThan(NOW.minusSeconds(86400))).thenReturn(1L);
    assertThat(service.openCount()).isEqualTo(4);
    assertThat(service.staleCount()).isEqualTo(1);
  }
}
