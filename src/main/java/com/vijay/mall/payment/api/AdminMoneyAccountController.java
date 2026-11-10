package com.vijay.mall.payment.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.payment.app.BankReportService;
import com.vijay.mall.payment.app.MoneyAccountService;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Account;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Entry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin only (everything under /admin): the shop's money accounts, their books and hand-entered movements. */
@RestController
@RequestMapping("/api/v1/admin/money-accounts")
public class AdminMoneyAccountController {
  private final MoneyAccountService accounts;
  private final BankReportService reports;
  private AdminOps ops;

  /** Makes repeats of a create with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired(required = false)
  void setOps(AdminOps ops) { this.ops = ops; }

  /** Creates the controller. */
  public AdminMoneyAccountController(MoneyAccountService accounts, BankReportService reports) {
    this.accounts = accounts;
    this.reports = reports;
  }

  /** Request body of a create or an update. */
  @Schema(name = "MoneyAccountBody")
  public record Body(String name, String kind, String provider, String identifier, String ifsc, UUID settlesToAccountId, String note) {
    MoneyAccountService.Input input() { return new MoneyAccountService.Input(name, kind, provider, identifier, ifsc, settlesToAccountId, note); }
  }

  /** Request body of a hand-entered movement. {@code at} defaults to now. */
  public record EntryBody(String direction, long amountPaise, Instant at, String note) {}

  /** Accounts A to Z. kind, active and q (name, provider, identifier or IFSC) are optional filters. */
  @GetMapping
  public Page<Account> list(@RequestParam(required = false) String kind, @RequestParam(required = false) Boolean active,
                            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
    return accounts.list(kind, active, q, page, size);
  }

  /** One account. */
  @GetMapping("/{id}")
  public Account get(@PathVariable UUID id) { return accounts.get(id); }

  /** Creates an account. With an Idempotency-Key a repeat returns the first account. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Account create(@RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody Body b) {
    UUID actor = CurrentUser.id();
    return AdminOps.run(ops, key, "money-account-create", actor, AdminOps.fingerprint(b), () -> accounts.create(actor, b.input()),
        a -> a.id().toString(), id -> accounts.get(UUID.fromString(id)));
  }

  /** Changes an account's details. */
  @PutMapping("/{id}")
  public Account update(@PathVariable UUID id, @RequestBody Body b) { return accounts.update(CurrentUser.id(), id, b.input()); }

  /** Stops offering the account for new receipts. */
  @PostMapping("/{id}/deactivate")
  public Account deactivate(@PathVariable UUID id) { return accounts.deactivate(CurrentUser.id(), id); }

  /** Offers the account again. */
  @PostMapping("/{id}/activate")
  public Account activate(@PathVariable UUID id) { return accounts.activate(CurrentUser.id(), id); }

  /** Deletes an account nobody has used; otherwise {@code 409 ACCOUNT_IN_USE}. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { accounts.delete(CurrentUser.id(), id); }

  /** The account's book for a range (both days included, India time; default the last 30 days) with a running balance. */
  @GetMapping("/{id}/book")
  public BankReportService.Book book(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return reports.book(id, from, to, page, size);
  }

  /** Records a movement that no other screen creates (opening balance, bank charge). With an Idempotency-Key a repeat returns the first entry. */
  @PostMapping("/{id}/entries")
  @ResponseStatus(HttpStatus.CREATED)
  public Entry addEntry(@PathVariable UUID id, @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody EntryBody b) {
    UUID actor = CurrentUser.id();
    return AdminOps.run(ops, key, "money-entry", actor, AdminOps.fingerprint(id, b), () -> accounts.addEntry(actor, id, b.direction(), b.amountPaise(), b.at(), b.note()),
        e -> e.id().toString(), eid -> accounts.entry(UUID.fromString(eid)).orElse(null));
  }
}
