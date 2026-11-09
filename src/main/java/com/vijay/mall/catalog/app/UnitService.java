package com.vijay.mall.catalog.app;

import com.vijay.mall.catalog.domain.UnitOfMeasure;
import com.vijay.mall.catalog.infra.UnitRepository;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.cache.AppCaches;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.live.CatalogChanged;
import com.vijay.mall.common.live.LiveEvents;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin-managed list of units. Products keep the unit's code; every screen reads the names from here. */
@Service
public class UnitService {
  /** Unit code used when a product is saved without one. */
  static final String DEFAULT = "pc";

  private final UnitRepository units;
  private final AuditService audit;

  private LiveEvents live;

  /** Optional: tells open app screens about changes (not provided in plain unit tests). */
  @Autowired(required = false)
  void setLive(LiveEvents live) { this.live = live; }

  private void changed() { if (live != null) live.area(CatalogChanged.UNITS); }

  /** Creates the service. */
  public UnitService(UnitRepository units, AuditService audit) {
    this.units = units;
    this.audit = audit;
  }

  private AppCaches caches;

  /** Optional (plain unit tests run without it). */
  @Autowired(required = false)
  void setCaches(AppCaches caches) { this.caches = caches; }

  /** Units a product can be given (for the pickers and the customer app); served from a cache when one is available. */
  public List<UnitOfMeasure> active() {
    return caches == null ? units.allActive() : caches.get(AppCaches.Name.UNITS, "active", units::allActive);
  }

  /** Every unit, including switched-off ones, for the admin list. */
  public List<UnitOfMeasure> all() { return units.all(); }

  /**
   * The unit's own spelling of a code the admin typed ("KG" becomes "kg"). A blank code gives the default unit. A code
   * that is not an active unit is refused, so a product can only use units the admin has set up.
   */
  public String canonical(String code) {
    String wanted = code == null || code.isBlank() ? DEFAULT : code.strip();
    return units.findIgnoreCase(wanted).filter(UnitOfMeasure::active).map(UnitOfMeasure::code)
        .orElseThrow(() -> ApiException.validation("unit", "unknown unit '" + wanted + "'; add it under units first"));
  }

  /** Same, but keeps the unit a product already has when it is blank or has since been switched off. */
  public String canonicalOrKeep(String code, String current) {
    if (code == null || code.isBlank()) return current == null || current.isBlank() ? canonical(null) : current;
    return units.findIgnoreCase(code.strip()).filter(u -> u.active() || u.code().equals(current)).map(UnitOfMeasure::code)
        .orElseThrow(() -> ApiException.validation("unit", "unknown unit '" + code.strip() + "'; add it under units first"));
  }

  /**
   * Adds a unit; the code is trimmed and kept as typed.
   * @throws ApiException validation error when the code or English name is blank, the code is longer than 20 characters or
   * {@code soldBy} is not {@code UNIT} / {@code WEIGHT}; {@code UNIT_EXISTS} (conflict) when the code exists ignoring case
   */
  @Transactional
  public UnitOfMeasure create(UnitOfMeasure in, UUID actor) {
    UnitOfMeasure u = clean(in, in.code());
    if (u.code().isEmpty()) throw ApiException.validation("code", "required");
    if (u.code().length() > 20) throw ApiException.validation("code", "at most 20 characters");
    if (units.findIgnoreCase(u.code()).isPresent()) throw ApiException.conflict("UNIT_EXISTS", "That unit already exists", Map.of());
    UnitOfMeasure saved = units.insert(u);
    audit.log(actor, "CREATE", "unit", saved.code(), saved.code() + " / " + saved.nameEn());
    changed();
    return saved;
  }

  /**
   * Changes a unit. When the code changes, every product using the old code moves to the new one, so the new spelling
   * shows everywhere at once.
   */
  @Transactional
  public UnitOfMeasure update(String code, UnitOfMeasure in, UUID actor) {
    UnitOfMeasure current = units.findIgnoreCase(code).orElseThrow(() -> ApiException.notFound("Unit"));
    UnitOfMeasure u = clean(in, in.code() == null || in.code().isBlank() ? current.code() : in.code());
    if (!u.active() && current.active() && units.productsUsing(current.code()) > 0 && units.allActive().size() <= 1) {
      throw ApiException.validation("active", "keep at least one active unit");
    }
    String from = current.code();
    if (!u.code().equals(from)) {
      if (u.code().length() > 20) throw ApiException.validation("code", "at most 20 characters");
      units.findIgnoreCase(u.code()).filter(o -> !o.code().equals(from))
          .ifPresent(o -> { throw ApiException.conflict("UNIT_EXISTS", "That unit already exists", Map.of()); });
      units.rename(from, u.code());
    }
    UnitOfMeasure saved = units.update(u.code(), u).orElseThrow(() -> ApiException.notFound("Unit"));
    audit.log(actor, "UPDATE", "unit", saved.code(), from + " -> " + saved.code());
    changed();
    if (live != null && !from.equals(saved.code())) live.catalog(); // products using the unit now show its new code
    return saved;
  }

  /** Deletes an unused unit; one that products use can only be switched off. */
  @Transactional
  public void delete(String code, UUID actor) {
    UnitOfMeasure u = units.findIgnoreCase(code).orElseThrow(() -> ApiException.notFound("Unit"));
    long used = units.productsUsing(u.code());
    if (used > 0) {
      throw ApiException.conflict("UNIT_IN_USE", used + " products use this unit; switch it off instead", Map.of("products", used));
    }
    units.delete(u.code());
    audit.log(actor, "DELETE", "unit", u.code(), u.code());
    changed();
  }

  private static UnitOfMeasure clean(UnitOfMeasure in, String code) {
    String c = code == null ? "" : code.strip();
    String name = in.nameEn() == null ? "" : in.nameEn().strip();
    if (name.isEmpty()) throw ApiException.validation("nameEn", "required");
    String soldBy = in.soldBy() == null || in.soldBy().isBlank() ? UnitOfMeasure.UNIT : in.soldBy().strip().toUpperCase();
    if (!soldBy.equals(UnitOfMeasure.UNIT) && !soldBy.equals(UnitOfMeasure.WEIGHT)) {
      throw ApiException.validation("soldBy", "UNIT or WEIGHT");
    }
    return new UnitOfMeasure(c, name, in.nameGu() == null ? "" : in.nameGu().strip(), soldBy, in.sortOrder(), in.active());
  }
}
