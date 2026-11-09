package com.vijay.mall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@SuppressWarnings({"unchecked", "rawtypes"})
class BroadcastProcessorTest {
  private static final Instant NOW = Instant.parse("2027-01-01T00:00:00Z");
  private final BroadcastRepository store = mock(BroadcastRepository.class);
  private final SegmentQueryRepository segments = mock(SegmentQueryRepository.class);
  private final ProfileService profiles = mock(ProfileService.class);
  private final PushSender push = mock(PushSender.class);
  private final BroadcastProcessor processor =
      new BroadcastProcessor(store, segments, profiles, push, Clock.fixed(NOW, ZoneOffset.UTC));
  private final UUID id = UUID.randomUUID();

  private static List<UUID> users(int n) {
    return IntStream.range(0, n).mapToObj(i -> new UUID(0, i + 1)).toList();
  }

  private BroadcastRepository.Job job(String audience, List<UUID> recipients, UUID last, Segment segment) {
    return new BroadcastRepository.Job(id, audience, "Title", "Body", "ref", recipients, last, segment, NOW);
  }

  private void claims(BroadcastRepository.Job job) {
    when(store.claim(eq(NOW), eq(NOW.minus(Duration.ofMinutes(2))), eq(3))).thenReturn(Optional.of(job)).thenReturn(Optional.empty());
  }

  @Test
  void aThousandTwoHundredAndOneRecipientsAreWrittenInThreeBatchesAndPushedInChunksOfAtMost500() {
    List<UUID> everyone = users(1201);
    claims(job("USERS", everyone, null, null));
    when(store.deliverBatch(eq(id), any(), anyList(), eq("Title"), eq("Body"), eq("ref"), eq(NOW))).thenAnswer(i -> i.getArgument(2));
    when(profiles.deviceTokensOf(anyList())).thenAnswer(i -> ((List<UUID>) i.getArgument(0)).stream().flatMap(u -> Stream.of("t" + u, "u" + u)).toList());

    processor.tick();

    ArgumentCaptor<List<UUID>> batches = ArgumentCaptor.forClass(List.class);
    verify(store, times(3)).deliverBatch(eq(id), any(), batches.capture(), any(), any(), any(), any());
    assertThat(batches.getAllValues().stream().map(List::size)).containsExactly(500, 500, 201);
    assertThat(batches.getAllValues().stream().flatMap(List::stream).distinct()).hasSize(1201);
    ArgumentCaptor<List<String>> pushes = ArgumentCaptor.forClass(List.class);
    verify(push, times(5)).send(pushes.capture(), eq("Title"), eq("Body"));
    assertThat(pushes.getAllValues()).allSatisfy(p -> assertThat(p.size()).isLessThanOrEqualTo(500));
    assertThat(pushes.getAllValues().stream().mapToInt(List::size).sum()).isEqualTo(2402);
    verify(store, times(5)).addPushed(eq(id), anyInt(), eq(NOW));
    verify(store).finish(id, NOW);
  }

  @Test
  void aResumedBroadcastContinuesAfterTheLastHandledUserAndNeverPushesTwiceToTheSameBatch() {
    List<UUID> everyone = users(1201).stream().sorted(Comparator.comparing(UUID::toString)).toList();
    UUID last = everyone.get(699);
    claims(job("USERS", everyone, last, null));
    when(store.deliverBatch(eq(id), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of());

    processor.tick();

    ArgumentCaptor<List<UUID>> batches = ArgumentCaptor.forClass(List.class);
    verify(store, times(2)).deliverBatch(eq(id), any(), batches.capture(), any(), any(), any(), any());
    assertThat(batches.getAllValues().get(0)).containsExactlyElementsOf(everyone.subList(700, 1200));
    assertThat(batches.getAllValues().get(1)).containsExactly(everyone.get(1200));
    verify(push, never()).send(anyList(), any(), any());
    verify(store).finish(id, NOW);
  }

  @Test
  void roleGroupsAreReadInKeysetPagesFromTheFirstIdOrFromTheCursor() {
    UUID u1 = new UUID(0, 1);
    UUID u2 = new UUID(0, 2);
    claims(job("CUSTOMERS", null, null, null));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), new UUID(0, 0), 500)).thenReturn(List.of(u1, u2));
    when(profiles.recipientIdsAfter(List.of("CUSTOMER"), u2, 500)).thenReturn(List.of());
    when(store.deliverBatch(any(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u1));
    when(profiles.deviceTokensOf(List.of(u1))).thenReturn(List.of("tok"));

    processor.tick();

    verify(store).deliverBatch(id, u2, List.of(u1, u2), "Title", "Body", "ref", NOW);
    verify(push).send(List.of("tok"), "Title", "Body");
    verify(store).addPushed(id, 1, NOW);
  }

  @Test
  void roleGroupResumesAfterTheCursor() {
    UUID cursor = new UUID(0, 7);
    claims(job("STAFF", null, cursor, null));
    when(profiles.recipientIdsAfter(List.of("ADMIN", "CASHIER"), cursor, 500)).thenReturn(List.of());
    processor.tick();
    verify(store, never()).deliverBatch(any(), any(), anyList(), any(), any(), any(), any());
    verify(store).finish(id, NOW);
  }

  @Test
  void segmentsAreEvaluatedAtTheMomentSendingStarted() {
    Segment s = new Segment("ALL", List.of());
    UUID u = new UUID(0, 5);
    claims(job("SEGMENT", null, null, s));
    when(segments.idsAfter(s, new UUID(0, 0), 500, NOW)).thenReturn(List.of(u));
    when(segments.idsAfter(s, u, 500, NOW)).thenReturn(List.of());
    when(store.deliverBatch(any(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u));
    when(profiles.deviceTokensOf(List.of(u))).thenReturn(List.of());

    processor.tick();

    verify(store).deliverBatch(id, u, List.of(u), "Title", "Body", "ref", NOW);
    verify(push, never()).send(anyList(), any(), any());
    verify(store, never()).addPushed(any(), anyInt(), any());
  }

  @Test
  void aFailingPushSenderNeverFailsTheBroadcast() {
    UUID u = new UUID(0, 5);
    claims(job("USERS", List.of(u), null, null));
    when(store.deliverBatch(any(), any(), anyList(), any(), any(), any(), any())).thenReturn(List.of(u));
    when(profiles.deviceTokensOf(List.of(u))).thenReturn(List.of("tok"));
    doThrow(new IllegalStateException("fcm down")).when(push).send(anyList(), any(), any());

    processor.tick();

    verify(store).finish(id, NOW);
  }

  @Test
  void aDatabaseFailureLeavesTheBroadcastOpenForALaterAttempt() {
    claims(job("USERS", users(2), null, null));
    when(store.deliverBatch(any(), any(), anyList(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

    processor.tick();

    verify(store, never()).finish(any(), any());
  }

  @Test
  void aFailedClaimIsLoggedAndSwallowed() {
    when(store.claim(any(), any(), anyInt())).thenThrow(new IllegalStateException("db down"));
    processor.tick();
    verify(store, never()).finish(any(), any());
  }

  @Test
  void nothingQueuedMeansNothingHappens() {
    when(store.claim(any(), any(), anyInt())).thenReturn(Optional.empty());
    processor.tick();
    verify(store, never()).deliverBatch(any(), any(), anyList(), any(), any(), any(), any());
  }

  @Test
  void severalOpenBroadcastsAreHandledInOneTick() {
    when(store.claim(any(), any(), anyInt())).thenReturn(Optional.of(job("USERS", List.of(), null, null)))
        .thenReturn(Optional.of(job("USERS", List.of(), null, null))).thenReturn(Optional.empty());
    processor.tick();
    verify(store, times(2)).finish(id, NOW);
  }
}
