package com.vijay.mall.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.notification.infra.BroadcastRepository;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A broadcast whose worker stalled is taken over by another worker, and from then on only the new worker may write: the old one can
 * neither move the cursor backwards nor mark the broadcast done. The real database decides, so the fence is tested with it.
 */
class BroadcastFencingIT extends IntegrationTestBase {
  @Autowired BroadcastRepository store;

  private UUID sendingBroadcast(boolean exact, long recipients) {
    UUID admin = jdbc.queryForObject("select id from users where email = 'admin@vijaysupermall.local'", UUID.class);
    if (admin == null) throw new AssertionError("admin missing");
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    store.insert(id, "ALL", "Fence", "Test", null, recipients, exact, null, null, null, List.of(), admin, now);
    // A fresh SENDING row is not stale for the real background worker (10 minutes), so only this test touches it.
    jdbc.update("update notification_broadcast set status = 'SENDING', attempts = 1, started_at = now(), updated_at = now() where id = ?", id);
    return id;
  }

  @AfterEach
  void closeOpenTestBroadcasts() {
    jdbc.update("update notification_broadcast set status = 'DONE', finished_at = now() where title = 'Fence' and status = 'SENDING'");
  }

  private UUID user() {
    String email = freshEmail();
    token(email);
    return jdbc.queryForObject("select id from users where email = ?", UUID.class, email);
  }

  @Test
  void aSupersededWorkerCannotMoveTheCursorBackwardsOrFinishTheBroadcast() {
    UUID id = sendingBroadcast(false, 10_000);
    UUID u1 = user();
    UUID u2 = user();
    Instant now = Instant.now();

    var first = store.deliverBatch(id, 1, u2, List.of(u1, u2), "Fence", "Test", null, now);
    assertThat(first).containsExactlyInAnyOrder(u1, u2);

    // the first worker stalls; another instance sees no sign of life and takes the broadcast over (attempt 2)
    var job = store.claim(now, now.plusSeconds(3600), 3).orElseThrow();
    assertThat(job.id()).isEqualTo(id);
    assertThat(job.attempt()).isEqualTo(2);
    assertThat(job.lastUserId()).isEqualTo(u2);

    assertThatThrownBy(() -> store.deliverBatch(id, 1, u1, List.of(u1), "Fence", "Test", null, now)).isInstanceOf(BroadcastRepository.Superseded.class);
    assertThatThrownBy(() -> store.heartbeat(id, 1, now)).isInstanceOf(BroadcastRepository.Superseded.class);
    assertThatThrownBy(() -> store.markPushed(id, 1, u1, 5, false, now)).isInstanceOf(BroadcastRepository.Superseded.class);
    assertThatThrownBy(() -> store.finish(id, 1, now)).isInstanceOf(BroadcastRepository.Superseded.class);

    Map<String, Object> row = jdbc.queryForMap("select status, last_user_id, delivered, pushed from notification_broadcast where id = ?", id);
    assertThat(row.get("status")).isEqualTo("SENDING");
    assertThat(row.get("last_user_id")).isEqualTo(u2);
    assertThat(((Number) row.get("delivered")).longValue()).isEqualTo(2);
    assertThat(((Number) row.get("pushed")).longValue()).isZero();

    // the new owner writes freely; the estimate becomes the real number when it finishes
    store.heartbeat(id, 2, now);
    store.markPushed(id, 2, u2, 3, true, now);
    store.finish(id, 2, now);
    var done = store.find(id).orElseThrow();
    assertThat(done.status()).isEqualTo("DONE");
    assertThat(done.recipients()).as("the estimate is replaced by what was delivered").isEqualTo(2);
    assertThat(done.recipientsExact()).isTrue();
    assertThat(done.pushed()).isEqualTo(3);
    assertThat(done.pushFailedBatches()).isEqualTo(1);
    assertThatThrownBy(() -> store.finish(id, 2, now)).as("done broadcasts accept no writes").isInstanceOf(BroadcastRepository.Superseded.class);
  }

  @Test
  void anExactRecipientCountIsKeptWhenTheBroadcastFinishes() {
    UUID id = sendingBroadcast(true, 7);
    store.finish(id, 1, Instant.now());
    assertThat(store.find(id).orElseThrow().recipients()).isEqualTo(7);
  }

  @Test
  void aBroadcastWithNoSignOfLifeIsReclaimedAndOneThatKeepsTouchingItIsNot() {
    UUID id = sendingBroadcast(true, 1);
    Instant now = Instant.now();
    assertThat(store.claim(now, now.minusSeconds(600), 3)).as("touched a moment ago: not stale").isEmpty();
    store.heartbeat(id, 1, now);
    assertThat(store.claim(now, now.minusSeconds(600), 3)).isEmpty();
    var again = store.claim(now, now.plusSeconds(3600), 3).orElseThrow();
    assertThat(again.id()).isEqualTo(id);
    assertThat(again.attempt()).isEqualTo(2);
    store.finish(id, 2, now);
  }

  @Test
  void theUsersOfOneBatchCanBeReadBackFromTheirInboxRowsForARePush() {
    UUID id = sendingBroadcast(true, 3);
    UUID a = user();
    UUID b = user();
    UUID c = user();
    List<UUID> sorted = List.of(a, b, c).stream().sorted(java.util.Comparator.comparing(UUID::toString)).toList();
    store.deliverBatch(id, 1, sorted.get(2), sorted, "Fence", "Test", null, Instant.now());
    assertThat(store.inboxUsersBetween(id, sorted.get(0), sorted.get(2))).containsExactly(sorted.get(1), sorted.get(2));
    assertThat(store.inboxUsersBetween(id, null, sorted.get(1))).containsExactly(sorted.get(0), sorted.get(1));
    store.finish(id, 1, Instant.now());
  }
}
