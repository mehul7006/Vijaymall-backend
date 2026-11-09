package com.vijay.mall.notification.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.notification.domain.Rule;
import com.vijay.mall.notification.domain.Segment;
import com.vijay.mall.support.IntegrationTestBase;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Segment previews and recipient estimates are bounded: a statement timeout, a small number at a time, and counts on one row set. */
class SegmentQueryLimitsIT extends IntegrationTestBase {
  @Autowired JdbcClient jdbc2;
  @Autowired SegmentQueryRepository repo;
  @Autowired PlatformTransactionManager txm;

  private <T> T inTx(java.util.function.Supplier<T> work) { return new TransactionTemplate(txm).execute(_ -> work.get()); }

  @Test
  void aQueryThatTakesLongerThanTheTimeoutIsAnsweredTooHeavyInsteadOfHanging() {
    var tight = new SegmentQueryRepository(jdbc2, 100, 2);
    assertThatThrownBy(() -> inTx(() -> tight.bounded(() -> jdbc2.sql("select pg_sleep(1)").query((rs, i) -> "x").single())))
        .isInstanceOfSatisfying(ApiException.class, e -> {
          assertThat(e.code()).isEqualTo("SEGMENT_TOO_HEAVY");
          assertThat(e.status().value()).isEqualTo(422);
        });
    assertThat(inTx(() -> tight.bounded(() -> jdbc2.sql("select 1").query(Integer.class).single()))).as("the connection is usable again").isEqualTo(1);
  }

  @Test
  void aFailureThatIsNotATimeoutIsNotDisguisedAsTooHeavy() {
    var repo2 = new SegmentQueryRepository(jdbc2, 5000, 2);
    assertThatThrownBy(() -> inTx(() -> repo2.bounded(() -> jdbc2.sql("select * from no_such_table").query(Integer.class).single())))
        .isNotInstanceOf(ApiException.class);
  }

  @Test
  void onlyAFewPreviewsRunAtOnceAndTheRestAreToldToRetry() throws Exception {
    var one = new SegmentQueryRepository(jdbc2, 5000, 1);
    CountDownLatch inside = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = Thread.ofVirtual().start(() -> inTx(() -> one.bounded(() -> {
      inside.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return 1;
    })));
    assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
    assertThatThrownBy(() -> inTx(() -> one.bounded(() -> 2))).isInstanceOfSatisfying(ApiException.class, e -> {
      assertThat(e.code()).isEqualTo("PREVIEW_BUSY");
      assertThat(e.status().value()).isEqualTo(429);
      assertThat(e.retryAfterSeconds()).isEqualTo(2L);
    });
    release.countDown();
    holder.join();
    assertThat(inTx(() -> one.bounded(() -> 3))).as("the slot was released").isEqualTo(3);
  }

  @Test
  void devicesAreCountedOnTheSameBoundedRowsAsRecipientsSoTheyNeverExceedThem() {
    for (int i = 0; i < 6; i++) {
      String email = freshEmail();
      token(email);
      jdbc.update("insert into device_token (id, user_id, fcm_token, platform, created_at) select gen_random_uuid(), id, ?, 'ANDROID', now() from users where email = ?",
          "tok-" + i + "-" + email, email);
    }
    Segment hasDevice = new Segment("ALL", List.of(Rule.of(Map.of("type", "HAS_DEVICE", "has", true))));
    var capped = repo.count(hasDevice, Instant.now(), 3);
    assertThat(capped.recipients()).isEqualTo(3);
    assertThat(capped.exact()).isFalse();
    assertThat(capped.withDevices()).isLessThanOrEqualTo(capped.recipients()).isEqualTo(3);
    var all = repo.count(hasDevice, Instant.now(), 10_000);
    assertThat(all.exact()).isTrue();
    assertThat(all.withDevices()).isEqualTo(all.recipients()).isGreaterThanOrEqualTo(6);
    var preview = repo.preview(hasDevice, Instant.now(), 10_000, 2);
    assertThat(preview.count().recipients()).isEqualTo(all.recipients());
    assertThat(preview.sample()).hasSize(2);
  }
}
