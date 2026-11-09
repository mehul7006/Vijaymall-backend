package com.vijay.mall.catalog.api;

import com.vijay.mall.catalog.app.UnitService;
import com.vijay.mall.catalog.domain.UnitOfMeasure;
import com.vijay.mall.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Units of measure: a public list for every screen, and admin add / edit / remove. */
@RestController
public class UnitController {
  /**
   * Request body to create or update a unit. {@code code} is required on create only (on update a blank code keeps the
   * current one); {@code soldBy} is {@code UNIT} (default) or {@code WEIGHT}.
   */
  public record Body(@Size(max = 20) String code, @NotBlank String nameEn, String nameGu, String soldBy,
                     Integer sortOrder, Boolean active) {
    UnitOfMeasure toUnit() {
      return new UnitOfMeasure(code, nameEn, nameGu, soldBy, sortOrder == null ? 0 : sortOrder, active == null || active);
    }
  }

  private final UnitService units;

  /** Creates the controller with the unit service. */
  public UnitController(UnitService units) { this.units = units; }

  /** Active units for the customer app, the counter and the product forms. */
  @GetMapping("/api/v1/units")
  public List<UnitOfMeasure> list() { return units.active(); }

  /** Admin: every unit, including switched-off ones. */
  @GetMapping("/api/v1/admin/units")
  public List<UnitOfMeasure> adminList() { return units.all(); }

  /** Admin: adds a unit; answers 201 Created, or 409 {@code UNIT_EXISTS} when the code is taken. */
  @PostMapping("/api/v1/admin/units")
  @ResponseStatus(HttpStatus.CREATED)
  public UnitOfMeasure create(@Valid @RequestBody Body b) { return units.create(b.toUnit(), CurrentUser.id()); }

  /** Admin: edits a unit; renaming the code moves every product that uses it. 404 when the code is unknown. */
  @PutMapping("/api/v1/admin/units/{code}")
  public UnitOfMeasure update(@PathVariable String code, @Valid @RequestBody Body b) {
    return units.update(code, b.toUnit(), CurrentUser.id());
  }

  /** Admin: deletes an unused unit; answers 204, or 409 {@code UNIT_IN_USE} when products still use it. */
  @DeleteMapping("/api/v1/admin/units/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String code) { units.delete(code, CurrentUser.id()); }
}
