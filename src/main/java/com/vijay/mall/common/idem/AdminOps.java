package com.vijay.mall.common.idem;

import com.vijay.mall.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Makes admin and staff actions safe to repeat. The app sends an optional {@code Idempotency-Key} header (a fresh UUID per tap,
 * reused on every retry of that tap). The first call does the work; a repeat with the same key and the same request does
 * nothing and returns the current state of what the first call touched. The same key with a different request is refused
 * with 409 {@code IDEMPOTENCY_KEY_REUSED}, so a reused key can never make an action disappear. Without a key the action runs as before.
 *
 * <p>The key is stored in the same database transaction as the action, so if the action fails the key is not used up and a retry
 * runs it again. Two calls with the same key at the same moment cannot both run: the second waits for the first and then sees it.
 */
@Component
public class AdminOps {
  private static final Logger log = LoggerFactory.getLogger(AdminOps.class);
  static final int MAX_KEY = 100;

  private final JdbcClient jdbc;
  private final TransactionTemplate tx;
  private final JsonMapper json;

  /**
   * Creates the helper.
   *
   * @param jdbc database access for the {@code admin_op} table
   * @param tx   transaction template, so the key and the action commit together
   * @param json used to keep the full result of actions whose answer cannot be rebuilt from an id
   */
  public AdminOps(JdbcClient jdbc, TransactionTemplate tx, JsonMapper json) {
    this.jdbc = jdbc;
    this.tx = tx;
    this.json = json;
  }

  /**
   * Runs an action once per key.
   *
   * @param key         the Idempotency-Key header, or null/blank to skip the check
   * @param action      a short name for the kind of action ("stock-adjust"); the same key may be used for different actions
   * @param actor       who is doing it (for support), may be null
   * @param fingerprintOf a hash of what was asked ({@link #fingerprint}); a repeat from the same person must ask for the same thing
   * @param work        does the action and returns what the caller should send back
   * @param ref         the id of what the action touched or created, stored so a repeat can find it
   * @param replay      returns the current state of what the first call touched, given that id
   * @param <T>         what the action returns
   * @return the result of the first call, or the current state of what it touched when this is a repeat
   * @throws ApiException 409 {@code IDEMPOTENCY_KEY_REUSED} when the key was used for a different request, or
   *                      {@code IDEMPOTENT_RESULT_GONE} when what the first call created no longer exists
   */
  public <T> T once(String key, String action, UUID actor, String fingerprintOf, Supplier<T> work, Function<T, String> ref, Function<String, T> replay) {
    if (key == null || key.isBlank()) return work.get();
    String k = key.strip();
    if (k.length() > MAX_KEY) throw ApiException.validation("Idempotency-Key", "must be at most " + MAX_KEY + " characters");
    // a key belongs to one person's request: someone else sending the same key and body is not a repeat
    String fingerprint = fingerprintOf + "|" + actor;
    return tx.execute(status -> {
      int added = jdbc.sql("insert into admin_op (action, op_key, actor, req_hash) values (:a, :k, :u, :h) on conflict do nothing")
          .param("a", action).param("k", k).param("u", actor).param("h", fingerprint).update();
      if (added == 0) return repeat(action, k, fingerprint, replay);
      T result = work.get();
      String r = ref.apply(result);
      if (r != null) jdbc.sql("update admin_op set result_ref = :r where action = :a and op_key = :k").param("r", r).param("a", action).param("k", k).update();
      return result;
    });
  }

  /**
   * Like {@link #once} for actions whose answer cannot be rebuilt from an id (for example a stock take's list of changes): the
   * full result is kept and a repeat returns exactly that.
   *
   * @param type the class of the result, to read it back
   * @param <T>  what the action returns
   * @return the result of the first call, or that same result again on a repeat
   */
  public <T> T onceStored(String key, String action, UUID actor, String fingerprintOf, Supplier<T> work, Class<T> type) {
    return once(key, action, actor, fingerprintOf, work, r -> json.writeValueAsString(r), stored -> json.readValue(stored, type));
  }

  private <T> T repeat(String action, String key, String fingerprint, Function<String, T> replay) {
    var stored = jdbc.sql("select result_ref, req_hash from admin_op where action = :a and op_key = :k").param("a", action).param("k", key)
        .query((r, i) -> new String[] {r.getString("result_ref"), r.getString("req_hash")}).optional().orElse(new String[2]);
    if (stored[1] != null && fingerprint != null && !stored[1].equals(fingerprint)) {
      throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request: use a new key", Map.of());
    }
    log.info("Repeat of {} ignored key={}", action, key);
    T replayed = stored[0] == null ? null : replay.apply(stored[0]);
    if (replayed == null) {
      throw ApiException.conflict("IDEMPOTENT_RESULT_GONE", "The first request was applied, but what it created is no longer there", Map.of());
    }
    return replayed;
  }

  /**
   * Same as {@link #once} but also works when no AdminOps is wired (plain unit tests): then the action just runs.
   *
   * @param ops the helper, or null
   * @see #once
   */
  public static <T> T run(AdminOps ops, String key, String action, UUID actor, String fingerprint, Supplier<T> work, Function<T, String> ref,
                          Function<String, T> replay) {
    return ops == null ? work.get() : ops.once(key, action, actor, fingerprint, work, ref, replay);
  }

  /**
   * Same as {@link #onceStored} but also works when no AdminOps is wired (plain unit tests): then the action just runs.
   *
   * @param ops the helper, or null
   * @see #onceStored
   */
  public static <T> T runStored(AdminOps ops, String key, String action, UUID actor, String fingerprintOf, Supplier<T> work, Class<T> type) {
    return ops == null ? work.get() : ops.onceStored(key, action, actor, fingerprintOf, work, type);
  }

  /**
   * A short fingerprint of a request: the parts that decide what the action does (ids and the body), so a repeat can be told
   * from a different request that reuses the key.
   *
   * @param parts the target ids and the request body (records print all their fields)
   * @return a hex SHA-256
   */
  public static String fingerprint(Object... parts) {
    StringBuilder sb = new StringBuilder();
    for (Object p : parts) sb.append(p).append('|');
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Keys older than a month are of no use any more. */
  @Scheduled(cron = "0 30 3 * * *")
  public void purge() {
    int n = jdbc.sql("delete from admin_op where created_at < now() - interval '30 days'").update();
    if (n > 0) log.info("Purged {} old admin action keys", n);
  }
}
