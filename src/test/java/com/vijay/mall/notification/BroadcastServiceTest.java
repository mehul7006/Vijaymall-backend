package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.ratelimit.RateLimiter;
import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.app.Audience;
import com.vijay.mall.notification.app.BroadcastService;
import com.vijay.mall.notification.app.SegmentService;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.notification.infra.BroadcastRepository;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BroadcastServiceTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final BroadcastRepository store = mock(BroadcastRepository.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final SegmentService segments = mock(SegmentService.class);
  private final SegmentQueryRepository query = mock(SegmentQueryRepository.class);
  private final AuditService audit = mock(AuditService.class);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final BroadcastService service = new BroadcastService(store, profiles, segments, query, audit, clock, 2);
  private final UUID admin = UUID.randomUUID();

  private static BroadcastService.Request req(Audience a, List<UUID> ids, List<String> mobiles, String title, String body, String ref) {
    return new BroadcastService.Request(a, ids, mobiles, null, null, title, body, ref);
  }

  private static BroadcastService.Request simple(Audience a) { return req(a, null, null, "  Hello  ", " World ", null); }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fields(BroadcastService.Request r) {
    ApiException e = (ApiException) catchThrowable(() -> service.enqueue(admin, r));
    assertThat(e.code()).isEqualTo("VALIDATION_FAILED");
    return (Map<String, Object>) e.details().get("fields");
  }

  private static Throwable catchThrowable(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      return e;
    }
    throw new AssertionError("expected an exception");
  }

  private void storedAsAny() {
    when(store.find(any())).thenAnswer(i -> Optional.of(row((UUID) i.getArgument(0))));
  }

  private static BroadcastRepository.Row row(UUID id) {
    return new BroadcastRepository.Row(id, "ALL", "Hello", "World", null, "QUEUED", 3, 0, 0, 0, true, UUID.randomUUID(), NOW, null, null, List.of(), null, null);
  }

  @Test
  void aRoleGroupIsRecordedWithTrimmedTextAndAnExactCount() {
    storedAsAny();
    when(profiles.countRecipients(Audience.STAFF.roles())).thenReturn(3L);

    var view = service.enqueue(admin, simple(Audience.STAFF));

    assertThat(view.status()).isEqualTo("QUEUED");
    verify(store).insert(any(), eq("STAFF"), eq("Hello"), eq("World"), eq(null), eq(3L), eq(true), eq(null), eq(null), eq(null), eq(List.of()), eq(admin), eq(NOW));
    verify(audit).log(eq(admin), eq("NOTIFICATION_BROADCAST"), eq("notification_broadcast"), any(), eq("audience=STAFF recipients=3 title=Hello"));
  }

  @Test
  void validationReportsEveryProblemAtOnce() {
    assertThat(fields(req(null, null, null, "  ", "x".repeat(501), "r".repeat(101)))).containsKeys("audience", "title", "body", "ref");
    assertThat(fields(req(Audience.ALL, null, null, "t".repeat(81), null, null))).containsKeys("title", "body");
    verify(store, never()).insert(any(), any(), any(), any(), any(), anyLong(), anyBoolean(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void blankRefMeansNoRef() {
    storedAsAny();
    service.enqueue(admin, req(Audience.ALL, null, null, "t", "b", "   "));
    verify(store).insert(any(), eq("ALL"), eq("t"), eq("b"), eq(null), anyLong(), anyBoolean(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void explicitListsNeedPeopleAndStayWithinTheLimits() {
    assertThat(fields(req(Audience.USERS, null, null, "t", "b", null))).containsKey("userIds");
    assertThat(fields(req(Audience.USERS, Collections.emptyList(), List.of(), "t", "b", null))).containsKey("userIds");
    List<UUID> many = IntStream.range(0, 501).mapToObj(i -> UUID.randomUUID()).toList();
    assertThat(fields(req(Audience.USERS, many, null, "t", "b", null))).containsEntry("userIds", "at most 500 ids");
    List<String> numbers = IntStream.range(0, 501).mapToObj(i -> String.format("%010d", i)).toList();
    assertThat(fields(req(Audience.USERS, null, numbers, "t", "b", null))).containsEntry("mobiles", "at most 500 numbers");
    assertThat(fields(req(Audience.USERS, null, Arrays.asList("12345", null), "t", "b", null))).containsEntry("mobiles", "each number must be 10 digits");
  }

  @Test
  void listsAndSegmentsAreOnlyForTheirOwnAudience() {
    assertThat(fields(req(Audience.ALL, List.of(UUID.randomUUID()), null, "t", "b", null))).containsEntry("userIds", "only used when audience is USERS");
    assertThat(fields(req(Audience.CUSTOMERS, null, List.of("9876543210"), "t", "b", null))).containsKey("userIds");
    var withSegmentId = new BroadcastService.Request(Audience.ALL, null, null, UUID.randomUUID(), null, "t", "b", null);
    assertThat(fields(withSegmentId)).containsKey("segment");
    var withInline = new BroadcastService.Request(Audience.USERS, List.of(UUID.randomUUID()), null, null, new Segment("ALL", List.of()), "t", "b", null);
    assertThat(fields(withInline)).containsKey("segment");
  }

  @Test
  void anExplicitListIsResolvedDeduplicatedAndSkippedPeopleAreReported() {
    storedAsAny();
    UUID a = UUID.randomUUID();
    when(profiles.resolveRecipients(List.of(a), List.of("9876543210"))).thenReturn(new ProfileService.Recipients(List.of(a), List.of("0000000000")));

    service.enqueue(admin, req(Audience.USERS, List.of(a), List.of(" 9876543210 "), "t", "b", null));

    verify(store).insert(any(), eq("USERS"), any(), any(), any(), eq(1L), eq(true), eq(List.of(a)), eq(null), eq(null), eq(List.of("0000000000")), eq(admin), eq(NOW));
  }

  @Test
  void anExplicitListThatMatchesNobodyIsRefused() {
    when(profiles.resolveRecipients(any(), any())).thenReturn(new ProfileService.Recipients(List.of(), List.of("x")));
    assertThatThrownBy(() -> service.enqueue(admin, req(Audience.USERS, List.of(UUID.randomUUID()), null, "t", "b", null)))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("NO_RECIPIENTS");
          assertThat(e.status().value()).isEqualTo(422);
        });
  }

  @Test
  void aSegmentIsSnapshottedAndItsRecipientCountIsMarkedExactWhenUnderTheCap() {
    storedAsAny();
    UUID segmentId = UUID.randomUUID();
    Segment snapshot = new Segment("ALL", List.of());
    when(segments.resolve(segmentId, null)).thenReturn(new SegmentService.Resolved(segmentId, snapshot));
    when(query.count(eq(snapshot), eq(NOW), anyInt())).thenReturn(new SegmentQueryRepository.Count(42, 10, true));

    service.enqueue(admin, new BroadcastService.Request(Audience.SEGMENT, null, null, segmentId, null, "t", "b", null));

    verify(store).insert(any(), eq("SEGMENT"), any(), any(), any(), eq(42L), eq(true), eq(null), eq(segmentId), eq(snapshot), eq(List.of()), eq(admin), eq(NOW));
  }

  @Test
  void anAdminMayCreateOnlyAFewBroadcastsPerHour() {
    storedAsAny();
    service.setLimiter(new RateLimiter(clock));
    service.enqueue(admin, simple(Audience.ALL));
    service.enqueue(admin, simple(Audience.ALL));
    assertThatThrownBy(() -> service.enqueue(admin, simple(Audience.ALL))).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.status().value()).isEqualTo(429);
      assertThat(e.code()).isEqualTo("RATE_LIMITED");
      assertThat(e.details()).containsKey("retryAfterSeconds");
    });
    service.enqueue(UUID.randomUUID(), simple(Audience.ALL));
  }

  @Test
  void aBigSegmentIsOnlyEstimatedAndSaysSo() {
    storedAsAny();
    Segment snapshot = new Segment("ALL", List.of());
    when(segments.resolve(null, snapshot)).thenReturn(new SegmentService.Resolved(null, snapshot));
    when(query.count(eq(snapshot), eq(NOW), eq(BroadcastService.ESTIMATE_CAP))).thenReturn(new SegmentQueryRepository.Count(10_000, 4_000, false));

    service.enqueue(admin, new BroadcastService.Request(Audience.SEGMENT, null, null, null, snapshot, "t", "b", null));

    verify(store).insert(any(), eq("SEGMENT"), any(), any(), any(), eq(10_000L), eq(false), eq(null), eq(null), eq(snapshot), eq(List.of()), eq(admin), eq(NOW));
    verify(audit).log(eq(admin), any(), any(), any(), eq("audience=SEGMENT recipients=10000+ (estimate) title=t"));
  }

  @Test
  void nullIdsInAnExplicitListAreRefusedWithAFieldMessage() {
    assertThat(fields(req(Audience.USERS, Arrays.asList(UUID.randomUUID(), null), null, "t", "b", null)))
        .containsEntry("userIds", "must not contain empty values");
    verify(profiles, never()).resolveRecipients(any(), any());
  }

  @Test
  void aRequestThatFailsAfterValidationDoesNotUseUpTheHourlyQuota() {
    storedAsAny();
    service.setLimiter(new RateLimiter(clock));
    when(profiles.resolveRecipients(any(), any())).thenReturn(new ProfileService.Recipients(List.of(), List.of("x")));
    UUID unknownSegment = UUID.randomUUID();
    when(segments.resolve(eq(unknownSegment), any())).thenThrow(ApiException.notFound("Segment"));
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> service.enqueue(admin, req(Audience.USERS, List.of(UUID.randomUUID()), null, "t", "b", null))).isInstanceOf(ApiException.class);
      assertThatThrownBy(() -> service.enqueue(admin, new BroadcastService.Request(Audience.SEGMENT, null, null, unknownSegment, null, "t", "b", null)))
          .isInstanceOf(ApiException.class);
      assertThatThrownBy(() -> service.enqueue(admin, req(null, null, null, "", "", null))).isInstanceOf(ApiException.class);
    }
    service.enqueue(admin, simple(Audience.ALL));
    service.enqueue(admin, simple(Audience.ALL));
    assertThatThrownBy(() -> service.enqueue(admin, simple(Audience.ALL))).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("RATE_LIMITED"));
  }

  @Test
  void theViewNeverShowsFewerRecipientsThanAlreadyDeliveredAndSaysWhetherPushIsConfigured() {
    UUID id = UUID.randomUUID();
    var estimate = new BroadcastRepository.Row(id, "SEGMENT", "t", "b", null, "SENDING", 10_000, 25_000, 12, 3, false, UUID.randomUUID(), NOW, NOW, null,
        List.of(), null, null);
    when(store.find(id)).thenReturn(Optional.of(estimate));
    var unknownPush = service.get(id);
    assertThat(unknownPush.recipients()).isEqualTo(25_000);
    assertThat(unknownPush.recipientsExact()).isFalse();
    assertThat(unknownPush.pushConfigured()).isFalse();
    assertThat(unknownPush.pushFailedBatches()).isEqualTo(3);
    var sender = mock(com.vijay.mall.notification.app.PushSender.class);
    when(sender.isConfigured()).thenReturn(true);
    service.setPush(sender);
    assertThat(service.get(id).pushConfigured()).isTrue();
    var exact = new BroadcastRepository.Row(id, "ALL", "t", "b", null, "DONE", 7, 7, 0, 0, true, UUID.randomUUID(), NOW, NOW, NOW, List.of(), null, null);
    when(store.find(id)).thenReturn(Optional.of(exact));
    assertThat(service.get(id).recipients()).isEqualTo(7);
  }

  @Test
  void recordingAndAuditingRunInOneTransactionWhenOneIsAvailable() {
    storedAsAny();
    var tx = mock(org.springframework.transaction.support.TransactionOperations.class);
    when(tx.execute(any())).thenAnswer(i -> ((org.springframework.transaction.support.TransactionCallback<?>) i.getArgument(0)).doInTransaction(null));
    service.setTransactions(tx);
    service.enqueue(admin, simple(Audience.ALL));
    verify(tx).execute(any());
    verify(store).insert(any(), eq("ALL"), any(), any(), any(), anyLong(), anyBoolean(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void getAndListMapTheStoredRows() {
    UUID id = UUID.randomUUID();
    when(store.find(id)).thenReturn(Optional.of(row(id)));
    assertThat(service.get(id).audience()).isEqualTo(Audience.ALL);
    when(store.find(any())).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(404));

    when(store.page(0, 50)).thenReturn(new Page<>(List.of(row(id)), 0, 50, 1));
    var page = service.list(-3, 500);
    assertThat(page.items()).hasSize(1);
    assertThat(page.size()).isEqualTo(50);
    assertThat(page.page()).isZero();
  }

  @Test
  void audienceSizeCountsRolesAndRefusesTheOthers() {
    when(profiles.countRecipients(Audience.ADMINS.roles())).thenReturn(2L);
    when(profiles.countRecipientsWithDevices(Audience.ADMINS.roles())).thenReturn(1L);
    assertThat(service.size(Audience.ADMINS)).isEqualTo(new BroadcastService.Size(Audience.ADMINS, 2, 1));
    assertThatThrownBy(() -> service.size(Audience.USERS)).isInstanceOf(ApiException.class).hasMessageContaining("USERS");
    assertThatThrownBy(() -> service.size(Audience.SEGMENT)).isInstanceOf(ApiException.class).hasMessageContaining("segment preview");
  }
}
