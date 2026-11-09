package com.vijay.mall.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.vijay.mall.common.live.StockChanged;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class ErrorHandlingCoverageTest {
  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void integrityViolationWithoutAnSqlCauseIsAnInternalError() {
    var r = handler.integrity(new DataIntegrityViolationException("no sql cause"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(r.getBody().code()).isEqualTo("INTERNAL_ERROR");
  }

  @Test
  void sqlExceptionWithoutAStateIsSkippedAndTheChainIsWalked() {
    var noState = new SQLException("boom"); // getSQLState() == null
    var r = handler.integrity(new DataIntegrityViolationException("wrapped", noState));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void aStateFurtherDownTheCauseChainIsFound() {
    var deep = new RuntimeException("mid", new SQLException("dup", "23505"));
    var r = handler.integrity(new DataIntegrityViolationException("wrapped", deep));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(r.getBody().code()).isEqualTo("ALREADY_EXISTS");
  }

  @Test
  void aCauseThatPointsAtItselfDoesNotLoopForever() {
    var selfCause = new DataIntegrityViolationException("loop") {
      @Override public synchronized Throwable getCause() { return this; }
    };
    var r = handler.integrity(selfCause);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Test
  void apiExceptionWithoutDetailsHasAnEmptyMap() {
    var e = new ApiException(HttpStatus.BAD_REQUEST, "X", "msg", null);
    assertThat(e.details()).isEmpty();
    assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(e.getMessage()).isEqualTo("msg");
  }

  @Test
  void stockChangedCopiesItsIdsAndTreatsNullAsEmpty() {
    var at = Instant.parse("2027-01-01T00:00:00Z");
    assertThat(new StockChanged(null, "SALE", at).productIds()).isEmpty();
    var id = UUID.randomUUID();
    List<UUID> source = new ArrayList<>(List.of(id));
    var ev = new StockChanged(source, "SALE", at);
    source.clear();
    assertThat(ev.productIds()).containsExactly(id);
    assertThat(ev.reason()).isEqualTo("SALE");
  }
}
