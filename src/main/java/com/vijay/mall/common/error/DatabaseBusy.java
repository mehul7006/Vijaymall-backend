package com.vijay.mall.common.error;

import java.sql.SQLTransientConnectionException;
import java.util.Map;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * The one place that knows what "the database connection pool is full" looks like and how it is answered: {@code 503 SERVICE_BUSY}
 * with {@code Retry-After}. Used by the exception handler (controllers) and by the security filter chain (token checks), so both answer alike.
 */
public final class DatabaseBusy {
  /** Seconds a client is told to wait before retrying. */
  public static final int RETRY_AFTER_SECONDS = 2;
  /** Plain-words message shown to the customer. */
  public static final String MESSAGE = "The shop is very busy right now. Please try again in a moment.";

  private DatabaseBusy() {}

  /** True when the request failed only because no database connection became free in time (the pool is full): a busy signal, not a bug. */
  public static boolean causes(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof SQLTransientConnectionException
          || t instanceof CannotGetJdbcConnectionException
          || t instanceof CannotCreateTransactionException) return true;
    }
    return false;
  }

  /** The JSON body of the busy answer. */
  public static ErrorBody body(String traceId) {
    return new ErrorBody("SERVICE_BUSY", MESSAGE, traceId, Map.of("retryAfterSeconds", RETRY_AFTER_SECONDS));
  }
}
