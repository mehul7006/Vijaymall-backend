package com.vijay.mall.notification.infra;

import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.notification.domain.Segment;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a segment against the database. Only active customers who can sign in are ever selected (blocked, deleted and unclaimed
 * counter accounts never are). Results come in keyset pages by user id and counts stop at a cap, so nothing reads every user.
 *
 * <p>Previews and recipient estimates run in an interactive request, so they are bounded three ways: only a few run at the same time
 * (the rest are told to retry shortly), each runs under a database statement timeout, and counting stops at a cap. A segment that is
 * too heavy to count in time is answered {@code 422 SEGMENT_TOO_HEAVY} instead of tying up a connection.
 */
@Repository
public class SegmentQueryRepository {
  private static final String FROM = "from users u where u.role = 'CUSTOMER' and u.status = 'ACTIVE' "
      + "and not (u.source = 'POS' and not u.mobile_verified) and ";

  /**
   * How many people a segment reaches.
   *
   * @param recipients matching customers, at most the cap
   * @param withDevices how many of those same {@code recipients} have at least one push device (never more than recipients)
   * @param exact false when more people match than the cap
   */
  public record Count(long recipients, long withDevices, boolean exact) {}

  /** A matching customer for the preview, with the mobile number masked. */
  public record Sample(UUID id, String name, String mobile) {}

  /** A preview: the bounded count and a few examples. */
  public record Preview(Count count, List<Sample> sample) {}

  private static final String SQL_STATE_QUERY_CANCELED = "57014";

  private final JdbcClient jdbc;
  private final int timeoutMs;
  private final Semaphore gate;

  SegmentQueryRepository(JdbcClient jdbc, @Value("${app.notifications.preview-timeout-ms:5000}") int timeoutMs,
                         @Value("${app.notifications.preview-concurrency:2}") int concurrency) {
    this.jdbc = jdbc;
    this.timeoutMs = timeoutMs;
    this.gate = new Semaphore(concurrency);
  }

  /** The next {@code limit} matching user ids after {@code after}, in id order. This is the sender's walk: no preview limits apply. */
  public List<UUID> idsAfter(Segment segment, UUID after, int limit, Instant now) {
    var c = SegmentSql.compile(segment, now);
    Map<String, Object> params = new LinkedHashMap<>(c.params());
    params.put("after", after);
    params.put("lim", limit);
    return jdbc.sql("select u.id " + FROM + "u.id > :after and " + c.where() + " order by u.id limit :lim").params(params)
        .query(UUID.class).list();
  }

  /**
   * Counts matching customers up to {@code cap} and takes up to {@code n} examples, in one short transaction under the preview limits.
   *
   * @throws ApiException 429 {@code PREVIEW_BUSY} when too many previews run already, 422 {@code SEGMENT_TOO_HEAVY} when it does not finish in time
   */
  @Transactional(readOnly = true)
  public Preview preview(Segment segment, Instant now, int cap, int n) {
    return bounded(() -> new Preview(countRows(segment, now, cap), sampleRows(segment, now, n)));
  }

  /** Counts matching customers up to {@code cap} under the preview limits; {@code exact} is false when there are more. */
  @Transactional(readOnly = true)
  public Count count(Segment segment, Instant now, int cap) {
    return bounded(() -> countRows(segment, now, cap));
  }

  /** Runs bounded work: at most a few at once, under the statement timeout; a timeout becomes {@code 422 SEGMENT_TOO_HEAVY}. */
  <T> T bounded(Supplier<T> work) {
    if (!gate.tryAcquire()) {
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "PREVIEW_BUSY", "Other segment previews are running. Try again in a moment.",
          Map.of("retryAfterSeconds", 2));
    }
    try {
      jdbc.sql("select set_config('statement_timeout', :ms, true)").param("ms", String.valueOf(timeoutMs)).query(String.class).single();
      return work.get();
    } catch (DataAccessException e) {
      if (canceled(e)) {
        throw ApiException.unprocessable("SEGMENT_TOO_HEAVY", "This segment is too heavy to count right now. Narrow it with another condition or a shorter period.",
            Map.of());
      }
      throw e;
    } finally {
      gate.release();
    }
  }

  private static boolean canceled(Throwable e) {
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof SQLException sql && SQL_STATE_QUERY_CANCELED.equals(sql.getSQLState())) return true;
    }
    return false;
  }

  private Count countRows(Segment segment, Instant now, int cap) {
    var c = SegmentSql.compile(segment, now);
    Map<String, Object> params = new LinkedHashMap<>(c.params());
    params.put("lim", cap + 1);
    params.put("cap", cap);
    long[] n = jdbc.sql("select count(*) filter (where m.rn <= :cap) as n, "
        + "count(*) filter (where m.rn <= :cap and exists (select 1 from device_token d where d.user_id = m.id)) as dev, count(*) as total "
        + "from (select u.id, row_number() over () as rn " + FROM + c.where() + " limit :lim) m").params(params)
        .query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("dev"), rs.getLong("total")}).single();
    return new Count(n[0], n[1], n[2] <= cap);
  }

  private List<Sample> sampleRows(Segment segment, Instant now, int n) {
    var c = SegmentSql.compile(segment, now);
    Map<String, Object> params = new LinkedHashMap<>(c.params());
    params.put("lim", n);
    return jdbc.sql("select u.id, u.name, u.mobile " + FROM + c.where() + " order by u.id limit :lim").params(params)
        .query((rs, i) -> new Sample(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("mobile"))).list();
  }
}
