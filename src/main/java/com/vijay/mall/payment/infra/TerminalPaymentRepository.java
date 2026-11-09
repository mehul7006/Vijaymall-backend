package com.vijay.mall.payment.infra;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Stores the card terminal sessions the till starts ({@code card_terminal_session}). */
@Repository
public class TerminalPaymentRepository {
  /** A terminal session as stored. */
  public record Row(UUID id, String reference, String provider, long amountPaise, String state, String mode, String instructions, String qrPayload,
                    String qrImageUrl, String approvalCode, String rrn, UUID moneyAccountId, Instant createdAt, UUID posSaleId) {}

  private static final String COLUMNS = "id, reference, provider, amount_paise, state, mode, instructions, qr_payload, qr_image_url, approval_code, rrn, "
      + "money_account_id, created_at, pos_sale_id";

  private final JdbcClient jdbc;

  TerminalPaymentRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  private Row row(ResultSet rs, int i) throws SQLException {
    Timestamp at = rs.getTimestamp("created_at");
    return new Row(rs.getObject("id", UUID.class), rs.getString("reference"), rs.getString("provider"), rs.getLong("amount_paise"), rs.getString("state"),
        rs.getString("mode"), rs.getString("instructions"), rs.getString("qr_payload"), rs.getString("qr_image_url"), rs.getString("approval_code"),
        rs.getString("rrn"), rs.getObject("money_account_id", UUID.class), at.toInstant(), rs.getObject("pos_sale_id", UUID.class));
  }

  /** One session by id. */
  public Optional<Row> find(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from card_terminal_session where id = :id").param("id", id).query(this::row).optional();
  }

  /** The session with our reference, if any. */
  public Optional<Row> findByReference(String reference) {
    return jdbc.sql("select " + COLUMNS + " from card_terminal_session where reference = :r").param("r", reference).query(this::row).optional();
  }

  /** Saves a new session; false when the reference already exists. */
  @Transactional
  public boolean insert(Row r, UUID by, Instant now) {
    return jdbc.sql("insert into card_terminal_session (id, reference, provider, amount_paise, state, mode, instructions, qr_payload, qr_image_url, approval_code, "
        + "rrn, money_account_id, created_by, created_at, updated_at) values (:id, :ref, :p, :a, :s, :m, :i, :qp, :qu, :ac, :rrn, :acc, :by, :now, :now) "
        + "on conflict (reference) do nothing").param("id", r.id()).param("ref", r.reference()).param("p", r.provider()).param("a", r.amountPaise())
        .param("s", r.state()).param("m", r.mode()).param("i", r.instructions()).param("qp", r.qrPayload()).param("qu", r.qrImageUrl())
        .param("ac", r.approvalCode()).param("rrn", r.rrn()).param("acc", r.moneyAccountId()).param("by", by).param("now", Timestamp.from(now)).update() == 1;
  }

  /** Moves a pending session to a new state; false when it was no longer pending (another call got there first). */
  @Transactional
  public boolean closePending(UUID id, String state, String approvalCode, String rrn, Instant now) {
    return jdbc.sql("update card_terminal_session set state = :s, approval_code = coalesce(:ac, approval_code), rrn = coalesce(:rrn, rrn), updated_at = :now "
        + "where id = :id and state = 'PENDING'").param("s", state).param("ac", approvalCode).param("rrn", rrn).param("now", Timestamp.from(now))
        .param("id", id).update() == 1;
  }

  /** Locks one session for use on a bill. */
  @Transactional
  public Optional<Row> lock(UUID id) {
    return jdbc.sql("select " + COLUMNS + " from card_terminal_session where id = :id for update").param("id", id).query(this::row).optional();
  }

  /** Records the bill that used the session. */
  @Transactional
  public void markUsed(UUID id, UUID saleId, Instant now) {
    jdbc.sql("update card_terminal_session set pos_sale_id = :s, updated_at = :now where id = :id").param("s", saleId).param("now", Timestamp.from(now))
        .param("id", id).update();
  }
}
