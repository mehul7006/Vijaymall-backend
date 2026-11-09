package com.vijay.mall.payment.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.payment.infra.MoneyAccountRepository;
import com.vijay.mall.payment.infra.MoneyAccountRepository.Account;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop's money accounts (bank, gateway, UPI QR, card machine, cash drawer) and the rules that tag every receipt with one.
 */
@Service
public class MoneyAccountService {
  /** The kinds an account can have. */
  public static final Set<String> KINDS = Set.of("BANK", "GATEWAY", "UPI", "CARD_TERMINAL", "CASH");
  /** Kinds that are paid out into a bank account. */
  static final Set<String> SETTLING_KINDS = Set.of("GATEWAY", "UPI", "CARD_TERMINAL");
  /** Kinds a counter line of each mode may be tagged with. */
  private static final Map<String, Set<String>> KINDS_FOR_MODE = Map.of("CASH", Set.of("CASH"), "UPI", Set.of("UPI", "GATEWAY", "BANK"),
      "CARD", Set.of("CARD_TERMINAL", "GATEWAY", "BANK"));

  /** Fields the admin sends to create or change an account. */
  public record Input(String name, String kind, String provider, String identifier, UUID settlesToAccountId, String note) {}

  private final MoneyAccountRepository repo;
  private final RuntimeSettings settings;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public MoneyAccountService(MoneyAccountRepository repo, RuntimeSettings settings, AuditService audit, Clock clock) {
    this.repo = repo;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  /** Accounts A to Z; every filter optional. */
  public Page<Account> list(String kind, Boolean active, String q, int page, int size) {
    String k = kind == null || kind.isBlank() ? null : kind.strip().toUpperCase(Locale.ROOT);
    if (k != null && !KINDS.contains(k)) throw ApiException.validation("kind", "BANK, GATEWAY, UPI, CARD_TERMINAL or CASH");
    return repo.page(k, active, q == null || q.isBlank() ? null : q.strip(), Math.max(0, page), Page.clampSize(size));
  }

  /** One account, or 404. */
  public Account get(UUID id) { return repo.find(id).orElseThrow(() -> ApiException.notFound("Money account")); }

  /**
   * Creates an account.
   * @throws ApiException 400 with the bad field in {@code fields}; 409 {@code DUPLICATE_ACCOUNT_NAME}
   */
  @Transactional
  public Account create(UUID actor, Input in) {
    Account a = build(UUID.randomUUID(), in, true, clock.instant());
    if (repo.nameTaken(a.name(), null)) throw duplicate();
    repo.insert(a);
    audit.log(actor, "CREATE", "money_account", a.id(), a.name() + " kind=" + a.kind());
    return get(a.id());
  }

  /**
   * Changes an account's name, provider, identifier, settles-to account and note. The kind can only change while nothing uses the
   * account yet, because every old receipt would change meaning.
   */
  @Transactional
  public Account update(UUID actor, UUID id, Input in) {
    Account old = get(id);
    Account a = build(id, in, old.active(), old.createdAt());
    if (!a.kind().equals(old.kind()) && repo.referenced(id)) {
      throw ApiException.conflict("ACCOUNT_IN_USE", "The kind cannot change while payments or accounts use this account", Map.of("fields", Map.of("kind", "in use")));
    }
    if (a.settlesToAccountId() != null && a.settlesToAccountId().equals(id)) throw ApiException.validation("settlesToAccountId", "an account cannot settle into itself");
    if (repo.nameTaken(a.name(), id)) throw duplicate();
    repo.update(a, clock.instant());
    audit.log(actor, "UPDATE", "money_account", id, a.name() + " kind=" + a.kind());
    return get(id);
  }

  /** Stops offering the account for new receipts; old receipts keep pointing at it. The last active cash account cannot be switched off. */
  @Transactional
  public Account deactivate(UUID actor, UUID id) {
    Account a = get(id);
    if (!a.active()) return a;
    if ("CASH".equals(a.kind()) && repo.countActive("CASH") <= 1) {
      throw ApiException.conflict("LAST_CASH_ACCOUNT", "Keep at least one active cash account", Map.of());
    }
    if (repo.hasActiveFeeders(id)) {
      throw ApiException.conflict("ACCOUNT_IN_USE", "Active accounts still settle into this one", Map.of());
    }
    repo.setActive(id, false, clock.instant());
    audit.log(actor, "DEACTIVATE", "money_account", id, a.name());
    return get(id);
  }

  /** Switches a deactivated account on again. */
  @Transactional
  public Account activate(UUID actor, UUID id) {
    Account a = get(id);
    if (a.active()) return a;
    repo.setActive(id, true, clock.instant());
    audit.log(actor, "ACTIVATE", "money_account", id, a.name());
    return get(id);
  }

  /** Removes an account nobody has used; an account any receipt, settlement or other account refers to is kept ({@code 409 ACCOUNT_IN_USE}). */
  @Transactional
  public void delete(UUID actor, UUID id) {
    Account a = get(id);
    if (repo.referenced(id)) {
      throw ApiException.conflict("ACCOUNT_IN_USE", "This account has history: deactivate it instead of deleting it", Map.of());
    }
    repo.delete(id);
    audit.log(actor, "DELETE", "money_account", id, a.name());
  }

  /** Largest amount one hand-entered movement may carry: 10 crore rupees. */
  public static final long MAX_ENTRY_PAISE = 10_000_000_000L;

  /**
   * Adds a hand-entered movement to an account (an opening balance, a bank charge, interest).
   * @param direction IN or OUT
   * @param at when it happened; null means now
   * @throws ApiException 400 on a bad direction, amount or note; 404 for an unknown account
   */
  @Transactional
  public MoneyAccountRepository.Entry addEntry(UUID actor, UUID accountId, String direction, long amountPaise, Instant at, String note) {
    get(accountId);
    String dir = direction == null ? "" : direction.strip().toUpperCase(Locale.ROOT);
    if (!dir.equals("IN") && !dir.equals("OUT")) throw ApiException.validation("direction", "IN or OUT");
    if (amountPaise <= 0 || amountPaise > MAX_ENTRY_PAISE) throw ApiException.validation("amountPaise", "must be between 1 and " + MAX_ENTRY_PAISE);
    String text = note == null ? "" : note.strip();
    if (text.length() > 200) throw ApiException.validation("note", "at most 200 characters");
    Instant now = clock.instant();
    var e = new MoneyAccountRepository.Entry(UUID.randomUUID(), accountId, at == null ? now : at, dir, amountPaise, null, null, text, actor);
    repo.insertEntry(e, now);
    audit.log(actor, "MONEY_ENTRY", "money_account", accountId, dir + " amountPaise=" + amountPaise);
    return e;
  }

  /** A hand-entered movement by id (used to answer a repeated request). */
  public Optional<MoneyAccountRepository.Entry> entry(UUID id) { return repo.findEntry(id); }

  private Account build(UUID id, Input in, boolean active, Instant created) {
    String name = in.name() == null ? "" : in.name().strip();
    if (name.isEmpty() || name.length() > 80) throw ApiException.validation("name", "1 to 80 characters");
    String kind = in.kind() == null ? "" : in.kind().strip().toUpperCase(Locale.ROOT);
    if (!KINDS.contains(kind)) throw ApiException.validation("kind", "BANK, GATEWAY, UPI, CARD_TERMINAL or CASH");
    String provider = in.provider() == null || in.provider().isBlank() ? "manual" : in.provider().strip().toLowerCase(Locale.ROOT);
    if (provider.length() > 40) throw ApiException.validation("provider", "at most 40 characters");
    String identifier = in.identifier() == null ? "" : in.identifier().strip();
    if (identifier.length() > 60) throw ApiException.validation("identifier", "at most 60 characters");
    String note = in.note() == null ? "" : in.note().strip();
    if (note.length() > 300) throw ApiException.validation("note", "at most 300 characters");
    UUID settles = in.settlesToAccountId();
    if (settles != null) {
      if (!SETTLING_KINDS.contains(kind)) throw ApiException.validation("settlesToAccountId", "only a gateway, UPI or card terminal account settles into a bank");
      Account bank = repo.find(settles).orElseThrow(() -> ApiException.validation("settlesToAccountId", "no such account"));
      if (!"BANK".equals(bank.kind()) || !bank.active()) throw ApiException.validation("settlesToAccountId", "must be an active BANK account");
    }
    return new Account(id, name, kind, provider, identifier, settles, active, note, created, created);
  }

  private static ApiException duplicate() {
    return ApiException.conflict("DUPLICATE_ACCOUNT_NAME", "An account with this name already exists", Map.of("fields", Map.of("name", "already used")));
  }

  // ---------- tagging receipts ----------
  /**
   * The account an online payment of this provider is received in: the one named in the {@code payment.gatewayAccountId} setting,
   * else the first active GATEWAY account of that provider; empty when neither exists (the payment is then Unassigned).
   */
  public Optional<UUID> gatewayAccountFor(String provider) {
    Optional<UUID> configured = parse(settings.get(Settings.PAYMENT_GATEWAY_ACCOUNT)).flatMap(repo::find).filter(Account::active).map(Account::id);
    return configured.isPresent() ? configured : repo.firstActive("GATEWAY", provider).map(Account::id);
  }

  /**
   * The account for one counter payment line.
   * @param mode CASH, UPI, CARD or CREDIT
   * @param requested the account the till named, or null
   * @param field the request field for an error such as {@code payments[0].moneyAccountId}
   * @param keepInactive true for an offline bill replayed after the account was switched off: it is accepted, not refused
   * @return the account to store: the requested one, else the default for the mode (setting, or the cash drawer for CASH), else null
   * @throws ApiException 400 on {@code field} when the account does not exist, is switched off, or does not fit the mode
   */
  public UUID counterAccount(String mode, UUID requested, String field, boolean keepInactive) {
    if ("CREDIT".equals(mode)) {
      if (requested != null) throw ApiException.validation(field, "credit has no money account");
      return null;
    }
    if (requested != null) {
      Account a = repo.find(requested).orElseThrow(() -> ApiException.validation(field, "no such money account"));
      if (!a.active() && !keepInactive) throw ApiException.validation(field, "this money account is switched off");
      if (!KINDS_FOR_MODE.getOrDefault(mode, Set.of()).contains(a.kind())) throw ApiException.validation(field, a.kind() + " cannot receive " + mode);
      return a.id();
    }
    Optional<UUID> configured = parse(settings.get(defaultSetting(mode))).flatMap(repo::find).filter(Account::active).map(Account::id);
    if (configured.isPresent()) return configured.get();
    return "CASH".equals(mode) ? repo.firstActive("CASH", null).map(Account::id).orElse(null) : null;
  }

  /** One way the cashier can take a payment: a vendor, optionally a terminal, and the account the money lands in. */
  public record Channel(String mode, UUID moneyAccountId, String provider, String name, String terminalId, String kind, @JsonProperty("default") boolean isDefault) {}

  /** The active channels per mode (CASH, UPI, CARD), with the shop's default of each mode flagged. */
  public List<Channel> channels() {
    List<Channel> out = new ArrayList<>();
    for (String mode : List.of("CASH", "UPI", "CARD")) {
      Optional<UUID> configured = parse(settings.get(defaultSetting(mode)));
      List<Account> accs = repo.activeForKinds(KINDS_FOR_MODE.get(mode).stream().filter(k -> !"BANK".equals(k)).toList());
      boolean any = accs.stream().anyMatch(a -> configured.filter(a.id()::equals).isPresent());
      for (int i = 0; i < accs.size(); i++) {
        Account a = accs.get(i);
        boolean def = any ? configured.filter(a.id()::equals).isPresent() : "CASH".equals(mode) && i == 0;
        out.add(new Channel(mode, a.id(), a.provider(), a.name(), a.identifier().isEmpty() ? null : a.identifier(), a.kind(), def));
      }
    }
    return out;
  }

  private static Settings.Def defaultSetting(String mode) {
    return switch (mode) {
      case "CASH" -> Settings.POS_DEFAULT_ACCOUNT_CASH;
      case "UPI" -> Settings.POS_DEFAULT_ACCOUNT_UPI;
      default -> Settings.POS_DEFAULT_ACCOUNT_CARD;
    };
  }

  /** The account a refund of a counter bill's line of this mode follows: the account of a bill line of the same mode, else the mode's default. */
  public UUID refundAccount(String mode, List<UUID> billAccountsOfMode) {
    return billAccountsOfMode.stream().filter(Objects::nonNull).findFirst().orElseGet(() -> counterAccount(mode, null, "refunds", true));
  }

  private static Optional<UUID> parse(String text) {
    try {
      return text == null || text.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(text.strip()));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
