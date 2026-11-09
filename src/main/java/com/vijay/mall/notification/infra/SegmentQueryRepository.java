package com.vijay.mall.notification.infra;

import com.vijay.mall.notification.domain.Segment;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Runs a segment against the database. Only active customers who can sign in are ever selected (blocked, deleted and unclaimed
 * counter accounts never are). Results come in keyset pages by user id and counts stop at a cap, so nothing reads every user.
 */
@Repository
public class SegmentQueryRepository {
  private static final String FROM = "from users u where u.role = 'CUSTOMER' and u.status = 'ACTIVE' "
      + "and not (u.source = 'POS' and not u.mobile_verified) and ";

  /**
   * How many people a segment reaches.
   *
   * @param recipients matching customers, at most the cap
   * @param withDevices how many of them have at least one push device
   * @param exact false when more people match than the cap
   */
  public record Count(long recipients, long withDevices, boolean exact) {}

  /** A matching customer for the preview, with the mobile number masked. */
  public record Sample(UUID id, String name, String mobile) {}

  private final JdbcClient jdbc;

  SegmentQueryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  /** The next {@code limit} matching user ids after {@code after}, in id order. */
  public List<UUID> idsAfter(Segment segment, UUID after, int limit, Instant now) {
    var c = SegmentSql.compile(segment, now);
    Map<String, Object> params = new LinkedHashMap<>(c.params());
    params.put("after", after);
    params.put("lim", limit);
    return jdbc.sql("select u.id " + FROM + "u.id > :after and " + c.where() + " order by u.id limit :lim").params(params)
        .query(UUID.class).list();
  }

  /** Counts matching customers up to {@code cap}; {@code exact} is false when there are more. */
  public Count count(Segment segment, Instant now, int cap) {
    var c = SegmentSql.compile(segment, now);
    Map<String, Object> params = new LinkedHashMap<>(c.params());
    params.put("lim", cap + 1);
    long[] n = jdbc.sql("select count(*) as n, count(*) filter (where exists (select 1 from device_token d where d.user_id = m.id)) as dev "
        + "from (select u.id " + FROM + c.where() + " limit :lim) m").params(params)
        .query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("dev")}).single();
    return new Count(Math.min(n[0], cap), n[1], n[0] <= cap);
  }

  /** Up to {@code n} matching customers for the admin to eyeball. */
  public List<Sample> sample(Segment segment, Instant now, int n) {
    var c = SegmentSql.compile(segment, now);
    Map<String, Object> params = new LinkedHashMap<>(c.params());
    params.put("lim", n);
    return jdbc.sql("select u.id, u.name, u.mobile " + FROM + c.where() + " order by u.id limit :lim").params(params)
        .query((rs, i) -> new Sample(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("mobile"))).list();
  }
}
