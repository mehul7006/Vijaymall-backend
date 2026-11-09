package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vijay.mall.identity.app.ProfileService;
import com.vijay.mall.notification.app.BroadcastProcessor;
import com.vijay.mall.notification.app.PushSender;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.notification.infra.BroadcastRepository;
import com.vijay.mall.notification.infra.SegmentQueryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"unchecked", "rawtypes"})
class BroadcastProcessorTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private static final int ATTEMPT = 2;
  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock = new Clock() {
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now.get(); }
  };
  private final BroadcastRepository store = mock(BroadcastRepository.class);
  private final SegmentQueryRepository segments = mock(SegmentQueryRepository.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final PushSender push = mock(PushSender.class);
  private final BroadcastProcessor processor = new BroadcastProcessor(store, segments, profiles, push, clock);
  private final UUID id = UUID.randomUUID();

  @BeforeEach
  void realSender() { when(push.isConfigured()).thenReturn(true); }

  private static List<UUID> users(int n) {
    return IntStream.range(0, n).mapToObj(i -> new UUID(0, i + 1)).toList();
  }

  private BroadcastRepository.Job job(String audience, List<UUID> recipients, UUID last, UUID pushedThrough, Segment segment) {
    return new BroadcastRepository.Job(id, audience, "Title", "Body", "ref", recipients, last, pushedThrough, ATTEMPT, segment, NOW);
  }

  private BroadcastRepository.Job job(String audience, List<UUID> recipients, UUID last, Segment segment) {
    return job(audience, recipients, last, last, segment);
  }

  private void claims(BroadcastRepository.Job job) {
    when(store.claim(eq(NOW), eq(NOW.minus(Duration.ofMinutes(10))), eq(3))).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
  }

  @Test
  void aThousandTwoHundredAndOneRecipientsAreWrittenInThreeBatchesAndPushedInChunksOfAtMost500() {
    List<UUID> everyone = users(1201);
    claims(job("USERS", everyone, null, null));
    when(store.deliverBatch(eq(id), eq(ATTEMPT), any(), anyList(), eq("Title"), eq("Body"), eq("ref"), eq(NOW))).thenAnswer(i -> i.getArgument(3));
    when(profiles.deviceTokensOf(anyList())).thenAnswer(i -> ((List<UUID>) i.getArgument(0)).stream().flatMap(u -> Stream.of("t" + u, "u" + u)).toList());

    processor.tick();

    ArgumentCaptor<List<UUID>> batches = ArgumentCaptor.forClass(List.class);
    verify(store, times(3)).deliverBatch(eq(id), eq(ATTEMPT), any(), batches.capture(), any(), any(), any(), any());
    assertThat(batches.getAllValues().stream().map(List::size)).containsExactly(500, 500, 201);
    assertThat(batches.getAllValues().stream().flatMap(List::stream).distinct()).hasSize(1201);
    ArgumentCaptor<List<String>> pushes = ArgumentCaptor.forClass(List.class);
    verify(push, times(5)).send(pushes.capture(), eq("Title"), eq("Body"), any());
    assertThat(pushes.getAllValues()).allSatisfy(p -> assertThat(p.size()).isLessThanOrEqualTo(500));
    assertThat(pushes.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(2402);
    ArgumentCaptor<Integer> devices = ArgumentCaptor.forClass(Integer.class);
    verify(store, times(3)).markPushed(eq(id), eq(ATTEMPT), any(), devices.capture(), eq(false), eq(NOW));
    assertThat(devices.getAllValues()).containsExactly(1000, 1000, 402);
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void aResumedBroadcastContinuesAfterTheLastHandledUserAndNeverPushesTwiceToTheSameBatch() {
    List<UUID> everyone = users(1201).stream().sorted(Comparator.comparing(UUID::toString)).toList();
    UUID last = everyone.get(699);
    claims(job("USERS", everyone, last, null));
    when(store.deliverBatch(eq(id), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of());

    processor.tick();

    ArgumentCaptor<List<UUID>> batches = ArgumentCaptor.forClass(List.class);
    verify(store, times(2)).deliverBatch(eq(id), anyInt(), any(), batches.capture(), any(), any(), any(), any());
    assertThat(batches.getAllValues().get(0)).containsExactlyElementsOf(everyone.subList(700, 1200));
    assertThat(batches.getAllValues().get(1)).containsExactly(everyone.get(1200));
    verify(push, never()).send(anyList(), any(), any(), any());
    verify(store, never()).inboxUsersBetween(any(), any(), any());
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void aCrashBetweenTheInboxCommitAndThePushPushesThatBatchAgainOnResume() {
    UUID u1 = new UUID(0, 1);
    UUID u2 = new UUID(0, 2);
    claims(job("CUSTOMERS", null, u2, u1, null));
    when(store.inboxUsersBetween(id, u1, u2)).thenReturn(List.of(u2));
    when(profiles.deviceTokensOf(List.of(u2))).thenReturn(List.of("tok2"));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), u2, 500)).thenReturn(List.of());

    processor.tick();

    verify(push).send(List.of("tok2"), "Title", "Body", Map.of("type", "ANNOUNCEMENT", "ref", "ref", "broadcastId", id.toString()));
    verify(store).markPushed(id, ATTEMPT, u2, 1, false, NOW);
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void roleGroupsAreReadInKeysetPagesFromTheFirstIdOrFromTheCursor() {
    UUID u1 = new UUID(0, 1);
    UUID u2 = new UUID(0, 2);
    claims(job("CUSTOMERS", null, null, null));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), new UUID(0, 0), 500)).thenReturn(List.of(u1, u2));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), u2, 500)).thenReturn(List.of());
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u1));
    when(profiles.deviceTokensOf(List.of(u1))).thenReturn(List.of("tok"));

    processor.tick();

    verify(store).deliverBatch(id, ATTEMPT, u2, List.of(u1, u2), "Title", "Body", "ref", NOW);
    verify(push).send(List.of("tok"), "Title", "Body", Map.of("type", "ANNOUNCEMENT", "ref", "ref", "broadcastId", id.toString()));
    verify(store).markPushed(id, ATTEMPT, u2, 1, false, NOW);
  }

  @Test
  void aBroadcastWithoutAReferencePushesNoRefKey() {
    UUID u1 = new UUID(0, 1);
    claims(new BroadcastRepository.Job(id, "CUSTOMERS", "Title", "Body", null, null, null, null, ATTEMPT, null, NOW));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), new UUID(0, 0), 500)).thenReturn(List.of(u1));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), u1, 500)).thenReturn(List.of());
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u1));
    when(profiles.deviceTokensOf(List.of(u1))).thenReturn(List.of("tok"));

    processor.tick();

    verify(push).send(List.of("tok"), "Title", "Body", Map.of("type", "ANNOUNCEMENT", "broadcastId", id.toString()));
  }

  @Test
  void roleGroupResumesAfterTheCursor() {
    UUID cursor = new UUID(0, 7);
    claims(job("STAFF", null, cursor, null));
    when(profiles.recipientIdsAfter(List.of("ADMIN", "CASHIER"), cursor, 500)).thenReturn(List.of());
    processor.tick();
    verify(store, never()).deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any());
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void segmentsAreEvaluatedAtTheMomentSendingStarted() {
    Segment s = new Segment("ALL", List.of());
    UUID u = new UUID(0, 5);
    claims(job("SEGMENT", null, null, s));
    when(segments.idsAfter(s, new UUID(0, 0), 500, NOW)).thenReturn(List.of(u));
    when(segments.idsAfter(s, u, 500, NOW)).thenReturn(List.of());
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u));
    when(profiles.deviceTokensOf(List.of(u))).thenReturn(List.of());

    processor.tick();

    verify(store).deliverBatch(id, ATTEMPT, u, List.of(u), "Title", "Body", "ref", NOW);
    verify(push, never()).send(anyList(), any(), any(), any());
    verify(store).markPushed(id, ATTEMPT, u, 0, false, NOW);
  }

  @Test
  void aFailingPushSenderNeverFailsTheBroadcastButTheBatchIsCountedAsFailedAndNothingIsCountedPushed() {
    UUID u = new UUID(0, 5);
    claims(job("USERS", List.of(u), null, null));
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u));
    when(profiles.deviceTokensOf(List.of(u))).thenReturn(List.of("tok"));
    doThrow(new IllegalStateException("fcm down")).when(push).send(anyList(), any(), any(), any());

    processor.tick();

    verify(store).markPushed(id, ATTEMPT, u, 0, true, NOW);
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void withoutARealPushProviderNothingIsReadOrSentAndNothingIsCountedPushed() {
    when(push.isConfigured()).thenReturn(false);
    UUID u = new UUID(0, 5);
    claims(job("USERS", List.of(u), null, null));
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u));

    processor.tick();

    verify(profiles, never()).deviceTokensOf(anyList());
    verify(push, never()).send(anyList(), any(), any(), any());
    verify(store).markPushed(id, ATTEMPT, u, 0, false, NOW);
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void aWorkerThatWasReplacedStopsAndNeverMarksTheBroadcastDone() {
    claims(job("USERS", users(2), null, null));
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenThrow(new BroadcastRepository.Superseded(id));

    processor.tick();

    verify(store, never()).finish(any(), anyInt(), any());
    verify(store, never()).markPushed(any(), anyInt(), any(), anyInt(), anyBoolean(), any());
  }

  @Test
  void aWorkerReplacedJustBeforeFinishingIsSwallowedToo() {
    claims(job("STAFF", null, new UUID(0, 7), null));
    when(profiles.recipientIdsAfter(any(), any(), anyInt())).thenReturn(List.of());
    doThrow(new BroadcastRepository.Superseded(id)).when(store).finish(any(), anyInt(), any());
    processor.tick();
    verify(store).finish(id, ATTEMPT, NOW);
  }

  @Test
  void aSlowPushCallKeepsTheBroadcastAliveWithAHeartbeatBeforeTheNextOne() {
    UUID u1 = new UUID(0, 1);
    UUID u2 = new UUID(0, 2);
    claims(job("USERS", List.of(u1, u2), null, null));
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u1));
    when(profiles.deviceTokensOf(List.of(u1))).thenReturn(IntStream.range(0, 600).mapToObj(i -> "t" + i).toList()); // two push calls
    org.mockito.Mockito.doAnswer(i -> { now.set(now.get().plus(BroadcastProcessor.HEARTBEAT).plusSeconds(1)); return null; })
        .when(push).send(anyList(), any(), any(), any());

    processor.tick();

    verify(store, times(1)).heartbeat(eq(id), eq(ATTEMPT), any());
  }

  @Test
  void aDatabaseFailureLeavesTheBroadcastOpenForALaterAttempt() {
    claims(job("USERS", users(2), null, null));
    when(store.deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

    processor.tick();

    verify(store, never()).finish(any(), anyInt(), any());
  }

  @Test
  void aFailedClaimIsLoggedAndSwallowed() {
    when(store.claim(any(), any(), anyInt())).thenThrow(new IllegalStateException("db down"));
    processor.tick();
    verify(store, never()).finish(any(), anyInt(), any());
  }

  @Test
  void nothingQueuedMeansNothingHappens() {
    when(store.claim(any(), any(), anyInt())).thenReturn(Optional.empty());
    processor.tick();
    verify(store, never()).deliverBatch(any(), anyInt(), any(), anyList(), any(), any(), any(), any());
  }

  @Test
  void severalOpenBroadcastsAreHandledInOneTick() {
    when(store.claim(any(), any(), anyInt())).thenReturn(Optional.of(job("USERS", List.of(), null, null)))
        .thenReturn(Optional.of(job("USERS", List.of(), null, null))).thenReturn(Optional.empty());
    processor.tick();
    verify(store, times(2)).finish(id, ATTEMPT, NOW);
  }
}
