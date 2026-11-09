package com.vijay.mall.purchasing.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.purchasing.app.SupplierService;
import com.vijay.mall.purchasing.app.SupplierService.View;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Request body for adding or changing a supplier: name (required), optional GSTIN, 2-digit state code, phone, address and payment terms in days. */
  public record SupplierBody(@NotBlank @Size(max = 120) String name, @Size(max = 15) String gstin, @Size(max = 2) String stateCode,
                             @Size(max = 20) String phone, @Size(max = 300) String address, @Min(0) @Max(365) Integer paymentTermsDays) {
    SupplierService.Input toInput() { return new SupplierService.Input(name, gstin, stateCode, phone, address, paymentTermsDays); }
  }

  private final SupplierService suppliers;

  /** Creates the controller. */
  public SupplierController(SupplierService suppliers) { this.suppliers = suppliers; }

  /** Suppliers by name; q searches name, phone and GSTIN; active filters on/off suppliers. */
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
