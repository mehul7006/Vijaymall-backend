package com.vijay.mall.purchasing.app;

import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.InvoiceNumbers;
import com.vijay.mall.purchasing.app.PurchaseService.PurchaseView;
import com.vijay.mall.purchasing.infra.PurchaseDocRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository;
import com.vijay.mall.purchasing.infra.PurchaseRepository.Header;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corrections to a recorded purchase that do not touch stock, lots, costs or tax: the invoice number and date, the due date, the notes, and the
 * GSTIN and state text of the supplier on the document. Quantities, costs, discounts, tax and items change stock and the money owed, so they
 * cannot be edited: a wrong quantity or cost is fixed with a purchase return (debit note) and a new purchase.
 *
 * <p>The change is a partial update: a field that is left out stays as it is, an empty text clears a text field and {@code dueDate: null}
 * clears the due date. Every change is audited with the old and the new value. Setting the same value again changes nothing (so a retry is
 * harmless).
 */
@Service
public class PurchaseEditService {
  /** The fields that may be changed. */
  public static final Set<String> FIELDS = Set.of("invoiceNo", "invoiceDate", "dueDate", "note", "attachmentNote", "supplierGstin", "supplierStateCode");

  private final PurchaseRepository repo;
  private final PurchaseDocRepository docs;
  private final PurchaseService purchases;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public PurchaseEditService(PurchaseRepository repo, PurchaseDocRepository docs, PurchaseService purchases, AuditService audit, Clock clock) {
    this.repo = repo;
    this.docs = docs;
    this.purchases = purchases;
    this.audit = audit;
    this.clock = clock;
  }

  private static String text(Object v, String field, int max) {
    if (v == null) return "";
    if (!(v instanceof String s)) throw ApiException.validation(field, "must be text");
    String t = s.strip();
    if (t.length() > max) throw ApiException.validation(field, "at most " + max + " characters");
    return t;
  }

  private static LocalDate date(Object v, String field) {
    if (!(v instanceof String s)) throw ApiException.validation(field, "must be a date (yyyy-MM-dd)");
    try {
      return LocalDate.parse(s.strip());
    } catch (DateTimeParseException e) {
      throw ApiException.validation(field, "must be a date (yyyy-MM-dd)");
    }
  }

  /**
   * Changes the given fields of a purchase and answers with the purchase and the duplicate-invoice warning for its (new) invoice number.
   *
   * @throws ApiException 404 unknown purchase; 400 for an unknown or locked field (the message says to use a return and a new purchase), a
   *     bad value, an invoice date in the future, a due date before the invoice date, or a state that does not match the GSTIN
   */
  @Transactional
  public PurchaseView edit(UUID actor, UUID id, Map<String, Object> fields) {
    if (fields == null || fields.isEmpty()) throw ApiException.validation("body", "give at least one field to change: " + FIELDS);
    for (String key : fields.keySet()) {
      if (!FIELDS.contains(key)) {
        throw ApiException.validation(key, "cannot be changed on a recorded purchase (only " + FIELDS + "): fix a wrong quantity or cost with a purchase return and a new purchase");
      }
    }
    Header h = repo.findForUpdate(id).orElseThrow(() -> ApiException.notFound("Purchase"));
    String invoiceNo = fields.containsKey("invoiceNo") ? text(fields.get("invoiceNo"), "invoiceNo", 60) : h.invoiceNo();
    String note = fields.containsKey("note") ? text(fields.get("note"), "note", 200) : h.note();
    String attachmentNote = fields.containsKey("attachmentNote") ? text(fields.get("attachmentNote"), "attachmentNote", 500) : h.attachmentNote();
    LocalDate invoiceDate = h.invoiceDate();
    if (fields.containsKey("invoiceDate")) {
      invoiceDate = date(fields.get("invoiceDate"), "invoiceDate");
      if (invoiceDate.isAfter(LocalDate.now(clock.withZone(InvoiceNumbers.SHOP_ZONE)))) throw ApiException.validation("invoiceDate", "cannot be in the future");
    }
    LocalDate dueDate = fields.containsKey("dueDate") && fields.get("dueDate") == null ? null
        : fields.containsKey("dueDate") ? date(fields.get("dueDate"), "dueDate") : h.dueDate();
    if (dueDate != null && dueDate.isBefore(invoiceDate)) throw ApiException.validation("dueDate", "cannot be before the invoice date");
    String gstin = fields.containsKey("supplierGstin") ? SupplierService.cleanGstin(text(fields.get("supplierGstin"), "supplierGstin", 15)) : h.supplierGstin();
    String state = h.supplierStateCode();
    if (fields.containsKey("supplierStateCode")) {
      state = SupplierService.cleanState(text(fields.get("supplierStateCode"), "supplierStateCode", 2), gstin, "supplierStateCode");
    } else if (fields.containsKey("supplierGstin") && !gstin.isEmpty()) {
      state = SupplierService.cleanState("", gstin, "supplierStateCode");
    }

    List<String> changes = new ArrayList<>();
    diff(changes, "invoiceNo", h.invoiceNo(), invoiceNo);
    diff(changes, "invoiceDate", h.invoiceDate(), invoiceDate);
    diff(changes, "dueDate", h.dueDate(), dueDate);
    diff(changes, "note", h.note(), note);
    diff(changes, "attachmentNote", h.attachmentNote(), attachmentNote);
    diff(changes, "supplierGstin", h.supplierGstin(), gstin);
    diff(changes, "supplierStateCode", h.supplierStateCode(), state);
    if (changes.isEmpty()) return purchases.recorded(h);
    docs.updateFields(id, invoiceNo, invoiceDate, dueDate, note, attachmentNote, gstin, state);
    audit.log(actor, "PURCHASE_EDIT", "purchase", id, String.join("; ", changes));
    return purchases.recorded(repo.find(id).orElseThrow());
  }

  private static void diff(List<String> out, String field, Object before, Object after) {
    if (!Objects.equals(before, after)) out.add(field + ": '" + (before == null ? "" : before) + "' -> '" + (after == null ? "" : after) + "'");
  }
}
