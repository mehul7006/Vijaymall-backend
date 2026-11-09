package com.vijay.mall.pos.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.Money;
import com.vijay.mall.pos.infra.PosReportRepository;
import com.vijay.mall.pos.infra.PosShiftRepository.Shift;
import com.vijay.mall.pos.infra.PosShiftRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash control for the counter. A cashier opens a shift with the float already in the drawer and closes it with the
 * cash counted. Expected cash = float + cash received for bills - cash refunded + cash taken as credit repayments, all
 * by that cashier since the shift opened. The difference is stored, so a missing or extra amount is never lost.
 */
@Service
public class ShiftService {
  /** expectedCashPaise and variancePaise are live while the shift is open (variance then only counts once closed). */
  public record ShiftView(UUID id, UUID cashierId, Instant openedAt, long openingFloatPaise, Instant closedAt,
                          long expectedCashPaise, Long countedCashPaise, Long variancePaise, String note, boolean open) {}

  static final int MAX_NOTE = 200;

  private final PosShiftRepository shifts;
  private final PosReportRepository report;
  private final AuditService audit;
  private final Clock clock;

  public ShiftService(PosShiftRepository shifts, PosReportRepository report, AuditService audit, Clock clock) {
    this.shifts = shifts;
    this.report = report;
    this.audit = audit;
    this.clock = clock;
  }

  /** Cash that should be in the drawer for this cashier from the shift's start until {@code to}. */
  private long expectedCash(Shift s, Instant to) {
    Map<String, Long> paid = report.paidByMode(s.openedAt(), to, s.cashierId());
    Map<String, Long> refunded = report.refundedByMode(s.openedAt(), to, s.cashierId());
    Map<String, Long> recovered = report.creditRecoveredByMode(s.openedAt(), to, s.cashierId());
    return s.openingFloatPaise() + paid.getOrDefault("CASH", 0L) - refunded.getOrDefault("CASH", 0L)
        + recovered.getOrDefault("CASH", 0L);
  }

  private ShiftView view(Shift s) {
    long expected = s.open() ? expectedCash(s, clock.instant().plusSeconds(1)) : s.expectedCashPaise();
    return new ShiftView(s.id(), s.cashierId(), s.openedAt(), s.openingFloatPaise(), s.closedAt(), expected, s.countedCashPaise(),
        s.variancePaise(), s.note(), s.open());
  }

  @Transactional
  public ShiftView open(UUID cashierId, long openingFloatPaise) {
    if (openingFloatPaise < 0) throw ApiException.validation("openingFloatPaise", "must not be negative");
    if (shifts.open(cashierId).isPresent()) throw alreadyOpen();
    try {
      return view(shifts.insert(cashierId, openingFloatPaise, clock.instant()));
    } catch (DataIntegrityViolationException e) {
      throw alreadyOpen(); // two opens at the same moment: the unique index lets only one win
    }
  }

  private static ApiException alreadyOpen() {
    return ApiException.conflict("SHIFT_ALREADY_OPEN", "You already have an open shift: close it first", Map.of());
  }

  /** The cashier's open shift, or 404 when none is open. */
  @Transactional(readOnly = true)
  public ShiftView current(UUID cashierId) {
    return view(shifts.open(cashierId).orElseThrow(() -> ApiException.notFound("Open shift")));
  }

  @Transactional(readOnly = true)
  public boolean hasOpenShift(UUID cashierId) { return shifts.open(cashierId).isPresent(); }

  /** Selling is refused until the cashier has opened a shift (only when the shop switches that rule on). */
  public void requireOpen(UUID cashierId) {
    if (!hasOpenShift(cashierId)) {
      throw ApiException.unprocessable("SHIFT_NOT_OPEN", "Open your shift (count the drawer) before billing", Map.of());
    }
  }

  /** Closes the shift with the counted cash. A difference to the expected amount needs a written reason. */
  @Transactional
  public ShiftView close(UUID cashierId, long countedCashPaise, String note) {
    if (countedCashPaise < 0) throw ApiException.validation("countedCashPaise", "must not be negative");
    String text = note == null ? "" : note.strip();
    if (text.length() > MAX_NOTE) throw ApiException.validation("note", "must be at most " + MAX_NOTE + " characters");
    Shift s = shifts.open(cashierId).orElseThrow(() -> ApiException.notFound("Open shift"));
    Instant now = clock.instant();
    long expected = expectedCash(s, now.plusSeconds(1));
    if (countedCashPaise != expected && text.isEmpty()) {
      throw ApiException.unprocessable("VARIANCE_NEEDS_NOTE", "The drawer is " + (countedCashPaise > expected ? "over" : "short")
          + " by Rs " + Money.plain(Math.abs(countedCashPaise - expected)) + ": write the reason",
          Map.of("expectedCashPaise", expected, "countedCashPaise", countedCashPaise));
    }
    shifts.close(s.id(), now, expected, countedCashPaise, text);
    audit.log(cashierId, "SHIFT_CLOSE", "pos_shift", s.id(), "expected=" + expected + " counted=" + countedCashPaise);
    return view(shifts.find(s.id()).orElseThrow());
  }

  /** Admin view of past shifts, newest first; cashierId null means everyone. */
  @Transactional(readOnly = true)
  public Page<ShiftView> list(UUID cashierId, int page, int size) {
    Page<Shift> p = shifts.page(cashierId, Math.max(0, page), Page.clampSize(size));
    return new Page<>(p.items().stream().map(this::view).toList(), p.page(), p.size(), p.total());
  }
}
