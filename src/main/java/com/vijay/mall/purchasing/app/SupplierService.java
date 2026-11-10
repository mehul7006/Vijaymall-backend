package com.vijay.mall.purchasing.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.purchasing.infra.SupplierRepository;
import com.vijay.mall.purchasing.infra.SupplierRepository.Stats;
import com.vijay.mall.purchasing.infra.SupplierRepository.Supplier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier master: create, edit, switch off and search suppliers, and find or create the supplier a purchase names. A supplier is
 * identified by its name compared trimmed, with single spaces and ignoring case, so "Agro  Traders " and "agro traders" are one supplier.
 */
@Service
public class SupplierService {
  /** What the admin types for a supplier. */
  @Schema(name = "SupplierInput")
  public record Input(String name, String gstin, String stateCode, String phone, String address, String email, String pincode,
                      Integer paymentTermsDays) {}

  /**
   * A supplier with what the purchases say about it. {@code balancePaise} (the same figure as {@code totalDuePaise}) is what the shop owes the
   * supplier, negative when the supplier owes the shop; {@code creditPaise} is the part that can be used on another purchase or refunded.
   */
  @Schema(name = "SupplierView")
  public record View(UUID id, String name, String phone, String gstin, String stateCode, String address, String email, String pincode,
                     int paymentTermsDays, boolean active,
                     Instant createdAt, String lastInvoiceNo, Instant lastPurchaseAt, long purchaseCount, long totalDuePaise,
                     long balancePaise, long creditPaise) {}

  private static final Pattern GSTIN = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
  private static final Pattern PHONE = Pattern.compile("^[0-9+ \\-]{0,20}$");
  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final Pattern PINCODE = Pattern.compile("^[0-9]{6}$");
  private static final int MAX_EMAIL = 254;
  private static final Pattern STATE = Pattern.compile("^[0-9]{2}$");

  private final SupplierRepository repo;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public SupplierService(SupplierRepository repo, AuditService audit, Clock clock) {
    this.repo = repo;
    this.audit = audit;
    this.clock = clock;
  }

  /** Checked and cleaned values of a supplier. */
  private record Clean(String name, String gstin, String state, String phone, String address, String email, String pincode, int terms) {}

  private static String text(String v) { return v == null ? "" : v.strip(); }

  /** Cleans a phone number: at most 20 digits, spaces, plus and dashes. */
  static String cleanPhone(String phone) {
    String p = text(phone);
    if (!PHONE.matcher(p).matches()) throw ApiException.validation("phone", "digits, spaces, + and - only, at most 20 characters");
    return p;
  }

  /** Cleans an e-mail address: empty or at most 254 characters of the simple form name@host.tld. */
  static String cleanEmail(String email) {
    String e = text(email);
    if (!e.isEmpty() && (e.length() > MAX_EMAIL || !EMAIL.matcher(e).matches())) throw ApiException.validation("email", "not a valid e-mail address (at most 254 characters)");
    return e;
  }

  /** Cleans a pincode: empty or exactly 6 digits. */
  static String cleanPincode(String pincode) {
    String p = text(pincode);
    if (!p.isEmpty() && !PINCODE.matcher(p).matches()) throw ApiException.validation("pincode", "6 digits");
    return p;
  }

  /** Cleans a GSTIN: upper case, empty or 15 characters in the GSTIN format. */
  static String cleanGstin(String gstin) {
    String g = text(gstin).toUpperCase(Locale.ROOT);
    if (!g.isEmpty() && !GSTIN.matcher(g).matches()) throw ApiException.validation("gstin", "not a valid GSTIN");
    return g;
  }

  /** Cleans a 2-digit GST state code that must agree with the GSTIN when both are given; empty falls back to the GSTIN's. */
  static String cleanState(String state, String gstin, String field) {
    String s = text(state);
    if (!s.isEmpty() && !STATE.matcher(s).matches()) throw ApiException.validation(field, "2 digit GST state code");
    if (gstin.isEmpty()) return s;
    String fromGstin = gstin.substring(0, 2);
    if (!s.isEmpty() && !s.equals(fromGstin)) throw ApiException.validation(field, "does not match the state in the GSTIN (" + fromGstin + ")");
    return fromGstin;
  }

  private static Clean clean(Input in) {
    String name = text(in.name()).replaceAll("\\s+", " ");
    int terms = in.paymentTermsDays() == null ? 0 : in.paymentTermsDays();
    String gstin = cleanGstin(in.gstin());
    return new Clean(name, gstin, cleanState(in.stateCode(), gstin, "stateCode"), cleanPhone(in.phone()), text(in.address()), cleanEmail(in.email()),
        cleanPincode(in.pincode()), terms);
  }

  private Supplier build(UUID id, Clean c, boolean active, Instant now) {
    return new Supplier(id, c.name(), SupplierRepository.key(c.name()), c.gstin(), c.state(), c.phone(), c.address(), c.email(), c.pincode(), c.terms(), active, now, now);
  }

  /** Adds a supplier. Fails with 409 {@code SUPPLIER_EXISTS} (naming the existing id) when the name is already used. */
  @Transactional
  public View create(UUID actor, Input in) {
    Clean c = clean(in);
    Supplier s = build(UUID.randomUUID(), c, true, clock.instant());
    if (!repo.insertIfAbsent(s)) throw exists(repo.findByKey(s.nameKey()).orElseThrow().id());
    audit.log(actor, "SUPPLIER_CREATE", "supplier", s.id(), s.name());
    return get(s.id());
  }

  private static ApiException exists(UUID existing) {
    return ApiException.conflict("SUPPLIER_EXISTS", "A supplier with this name already exists", Map.of("supplierId", existing,
        "fields", Map.of("name", "already used by another supplier")));
  }

  /** Changes a supplier's details (all fields are replaced). The name may not be another supplier's. */
  @Transactional
  public View update(UUID actor, UUID id, Input in) {
    Supplier before = repo.find(id).orElseThrow(() -> ApiException.notFound("Supplier"));
    Clean c = clean(in);
    Supplier next = build(id, c, before.active(), before.createdAt());
    repo.findByKey(next.nameKey()).filter(o -> !o.id().equals(id)).ifPresent(o -> { throw exists(o.id()); });
    repo.update(next, clock.instant());
    audit.log(actor, "SUPPLIER_UPDATE", "supplier", id, before.name() + " -> " + next.name());
    return get(id);
  }

  /** Switches a supplier off (it can no longer be picked on new purchases; history stays) or back on. */
  @Transactional
  public View setActive(UUID actor, UUID id, boolean active) {
    Supplier s = repo.find(id).orElseThrow(() -> ApiException.notFound("Supplier"));
    if (s.active() != active) {
      repo.setActive(id, active, clock.instant());
      audit.log(actor, active ? "SUPPLIER_ACTIVATE" : "SUPPLIER_DEACTIVATE", "supplier", id, s.name());
    }
    return get(id);
  }

  /** One supplier with its purchase figures; 404 when unknown. */
  public View get(UUID id) {
    Supplier s = repo.find(id).orElseThrow(() -> ApiException.notFound("Supplier"));
    return views(List.of(s)).getFirst();
  }

  /** Suppliers by name, searchable by name, phone, GSTIN or e-mail; {@code active} null shows both. */
  public Page<View> list(String q, Boolean active, int page, int size) {
    Page<Supplier> p = repo.page(q, active, Math.max(0, page), Page.clampSize(size));
    return p.withItems(views(p.items()));
  }

  private List<View> views(List<Supplier> suppliers) {
    Map<UUID, Stats> stats = repo.stats(suppliers.stream().map(Supplier::id).toList());
    return suppliers.stream().map(s -> {
      Stats t = stats.get(s.id());
      return new View(s.id(), s.name(), s.phone(), s.gstin(), s.stateCode(), s.address(), s.email(), s.pincode(), s.paymentTermsDays(), s.active(), s.createdAt(),
          t.lastInvoiceNo(), t.lastPurchaseAt(), t.purchaseCount(), t.totalDuePaise(), t.totalDuePaise(), t.creditPaise());
    }).toList();
  }

  /**
   * The supplier a purchase belongs to. With an id, that supplier (it must exist and be active). Otherwise the supplier whose name matches
   * (trimmed, ignoring case), created on the fly when there is none, so old apps that only send a name keep working. A phone, GSTIN or state
   * sent with the purchase fills in what the supplier is missing but never overwrites what is stored.
   *
   * @throws ApiException 400 on {@code supplierId} for an unknown or switched-off supplier, on {@code supplierName} when neither is given
   */
  @Transactional
  public Supplier resolve(UUID actor, UUID supplierId, String name, String phone, String gstin, String state) {
    String cleanGstin = cleanGstin(gstin);
    String cleanPhone = cleanPhone(phone);
    String cleanState = cleanState(state, cleanGstin, "supplierStateCode");
    Instant now = clock.instant();
    Supplier s;
    if (supplierId != null) {
      s = repo.find(supplierId).orElseThrow(() -> ApiException.validation("supplierId", "supplier does not exist"));
    } else {
      String n = text(name).replaceAll("\\s+", " ");
      if (n.isEmpty()) throw ApiException.validation("supplierName", "1 to 120 characters");
      Supplier fresh = new Supplier(UUID.randomUUID(), n, SupplierRepository.key(n), cleanGstin, cleanState, cleanPhone, "", "", "", 0, true, now, now);
      if (repo.insertIfAbsent(fresh)) audit.log(actor, "SUPPLIER_CREATE", "supplier", fresh.id(), n + " (from a purchase)");
      s = repo.findByKey(fresh.nameKey()).orElseThrow();
    }
    if (!s.active()) throw ApiException.validation(supplierId != null ? "supplierId" : "supplierName", "this supplier is switched off");
    repo.fillMissing(s.id(), cleanPhone, cleanGstin, cleanState, now);
    return repo.find(s.id()).orElseThrow();
  }
}
