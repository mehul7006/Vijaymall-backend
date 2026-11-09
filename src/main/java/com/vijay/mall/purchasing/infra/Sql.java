package com.vijay.mall.purchasing.infra;

import com.vijay.mall.common.persistence.BoundedCount;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Small helpers shared by the purchasing repositories. */
final class Sql {
  private Sql() { }

  /** A timestamp column as an instant. */
  static Instant instant(ResultSet r, String column) throws SQLException { return r.getObject(column, OffsetDateTime.class).toInstant(); }

  /** An instant as a value the driver binds to a {@code timestamptz}. */
  static OffsetDateTime ts(Instant t) { return OffsetDateTime.ofInstant(t, ZoneOffset.UTC); }

  /** Escapes {@code %}, {@code _} and the escape character itself for a LIKE pattern that uses {@code escape '\'}. */
  static String escapeLike(String text) { return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"); }

  /**
   * Counts the rows of a {@code from ... where ...} clause, never more than {@link BoundedCount#CAP} + 1.
   *
   * @param jdbc the client
   * @param fromWhere the table (with alias) and filter
   * @param params the named parameters of the filter
   * @return the bounded count
   */
  static long boundedCount(JdbcClient jdbc, String fromWhere, Map<String, Object> params) {
    var q = jdbc.sql("select count(*) from (select 1 from " + fromWhere + " limit " + (BoundedCount.CAP + 1) + ") bounded");
    params.forEach(q::param);
    return q.query(Long.class).single();
  }
}
