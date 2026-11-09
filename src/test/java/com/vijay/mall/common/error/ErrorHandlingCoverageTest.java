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

  @Test
  void anUploadThatIsNotMultipartIsA400() {
    var r = handler.notMultipart(new org.springframework.web.multipart.MultipartException("Current request is not a multipart request"));
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(r.getBody().code()).isEqualTo("VALIDATION_FAILED");
  }

  @Test
  void aClientThatDisconnectedIsNotAnError() {
    var res = new org.springframework.mock.web.MockHttpServletResponse();
    handler.clientGone(new org.springframework.web.context.request.async.AsyncRequestNotUsableException("Broken pipe"), res);
    assertThat(res.getStatus()).isEqualTo(200); // nothing was written
  }

  @Test
  void aFailureOnALiveStreamOrAnAlreadyStartedResponseSendsNoJsonBody() {
    var sse = new org.springframework.mock.web.MockHttpServletResponse();
    sse.setContentType("text/event-stream");
    assertThat(handler.other(new IllegalStateException("boom"), sse)).isNull();
    var committed = new org.springframework.mock.web.MockHttpServletResponse();
    committed.setCommitted(true);
    assertThat(handler.other(new IllegalStateException("boom"), committed)).isNull();
    var plain = new org.springframework.mock.web.MockHttpServletResponse();
    plain.setContentType("application/json");
    assertThat(handler.other(new IllegalStateException("boom"), plain).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    var untyped = new org.springframework.mock.web.MockHttpServletResponse();
    assertThat(handler.other(new IllegalStateException("boom"), untyped).getBody().code()).isEqualTo("INTERNAL_ERROR");
    assertThat(handler.other(new IllegalStateException("boom")).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
