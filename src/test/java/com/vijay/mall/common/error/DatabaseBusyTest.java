package com.vijay.mall.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

class DatabaseBusyTest {
  @Test
  void recognisesEveryPoolExhaustionShapeAnywhereInTheCauseChain() {
    var timeout = new SQLTransientConnectionException("timed out");
    assertThat(DatabaseBusy.causes(timeout)).isTrue();
    assertThat(DatabaseBusy.causes(new RuntimeException("x", new CannotGetJdbcConnectionException("pool", timeout)))).isTrue();
    assertThat(DatabaseBusy.causes(new CannotCreateTransactionException("tx", timeout))).isTrue();
    assertThat(DatabaseBusy.causes(new IllegalStateException("plain"))).isFalse();
    assertThat(DatabaseBusy.causes(null)).isFalse();
  }

  @Test
  void bodyCarriesTheRetryHint() {
    var b = DatabaseBusy.body("t1");
    assertThat(b.code()).isEqualTo("SERVICE_BUSY");
    assertThat(b.traceId()).isEqualTo("t1");
    assertThat(b.details()).containsEntry("retryAfterSeconds", 2);
  }
}
