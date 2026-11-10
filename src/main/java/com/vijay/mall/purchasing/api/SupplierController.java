package com.vijay.mall.purchasing.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.purchasing.app.SupplierCreditService;
import com.vijay.mall.purchasing.app.SupplierService;
import com.vijay.mall.purchasing.app.SupplierService.View;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

/** The supplier master. Everything under /admin is for the admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/suppliers")
public class SupplierController {
  private AdminOps ops;

  /** Makes a repeated create with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired
  void setOps(AdminOps ops) { this.ops = ops; }

  /** Request body for adding or changing a supplier: name (required), optional GSTIN, 2-digit state code, phone, address, e-mail, 6-digit pincode and payment terms in days. */
  public record SupplierBody(@NotBlank @Size(max = 120) String name, @Size(max = 15) String gstin, @Size(max = 2) String stateCode,
                             @Size(max = 20) String phone, @Size(max = 300) String address, @Size(max = 254) String email,
                             @Size(max = 6) String pincode, @Min(0) @Max(365) Integer paymentTermsDays) {
    SupplierService.Input toInput() { return new SupplierService.Input(name, gstin, stateCode, phone, address, email, pincode, paymentTermsDays); }
  }

  private final SupplierService suppliers;
  private final SupplierCreditService credit;

  /** Creates the controller. */
  public SupplierController(SupplierService suppliers, SupplierCreditService credit) {
    this.suppliers = suppliers;
    this.credit = credit;
  }

  /**
   * The supplier's statement: the balance at {@code from}, the purchases, payments, debit notes, credit uses and refunds in date order with a
   * running balance (one page, size 1 to 50), and the balance at {@code to}. from and to are India dates and optional.
   */
  @GetMapping("/{id}/statement")
  public SupplierCreditService.Statement statement(@PathVariable UUID id,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return credit.statement(id, from, to, page, size);
  }

  /** Records money the supplier paid back, taken from its credit. Needs an Idempotency-Key; 422 REFUND_EXCEEDS_CREDIT above the credit. */
  @PostMapping("/{id}/refunds")
  @ResponseStatus(HttpStatus.CREATED)
  public SupplierCreditService.RefundView refund(@PathVariable UUID id, @RequestHeader("Idempotency-Key") String key,
                                                 @Valid @RequestBody SupplierCreditService.RefundInput b) {
    return credit.refund(CurrentUser.id(), key, id, b);
  }

  /** The supplier's debit notes (credit notes), newest first, paged (size 1 to 50), with the amount used and still available as credit. */
  @GetMapping("/{id}/credit-notes")
  public Page<SupplierCreditService.CreditNoteView> creditNotes(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "20") int size) {
    return credit.creditNotes(id, page, size);
  }

  /** The latest refunds of a supplier (at most 50), newest first. */
  @GetMapping("/{id}/refunds")
  public List<SupplierCreditService.RefundView> refunds(@PathVariable UUID id) { return credit.refunds(id); }

  /** Suppliers by name; q searches name, phone, GSTIN and e-mail; active filters on/off suppliers. */
  @GetMapping
  public Page<View> list(@RequestParam(required = false) String q, @RequestParam(required = false) Boolean active,
                         @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
    return suppliers.list(q, active, page, size);
  }

  /** One supplier with its purchase figures; 404 when unknown. */
  @GetMapping("/{id}")
  public View get(@PathVariable UUID id) { return suppliers.get(id); }

  /** Adds a supplier; 409 SUPPLIER_EXISTS when the name is taken. An Idempotency-Key makes a retry return the first answer. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public View create(@Valid @RequestBody SupplierBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    UUID actor = CurrentUser.id();
    return AdminOps.run(ops, key, "supplier-create", actor, AdminOps.fingerprint(b), () -> suppliers.create(actor, b.toInput()),
        v -> v.id().toString(), ref -> suppliers.get(UUID.fromString(ref)));
  }

  /** Changes a supplier's details. */
  @PutMapping("/{id}")
  public View update(@PathVariable UUID id, @Valid @RequestBody SupplierBody b) { return suppliers.update(CurrentUser.id(), id, b.toInput()); }

  /** Switches a supplier off: it cannot be picked on new purchases, history stays. */
  @PostMapping("/{id}/deactivate")
  public View deactivate(@PathVariable UUID id) { return suppliers.setActive(CurrentUser.id(), id, false); }

  /** Switches a supplier back on. */
  @PostMapping("/{id}/activate")
  public View activate(@PathVariable UUID id) { return suppliers.setActive(CurrentUser.id(), id, true); }
}
