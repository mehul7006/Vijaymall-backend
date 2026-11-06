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
  public record Body(@Size(max = 20) String code, @NotBlank String nameEn, String nameGu, String soldBy,
                     Integer sortOrder, Boolean active) {
    UnitOfMeasure toUnit() {
      return new UnitOfMeasure(code, nameEn, nameGu, soldBy, sortOrder == null ? 0 : sortOrder, active == null || active);
    }
  }

  private final UnitService units;

  public UnitController(UnitService units) { this.units = units; }

  /** Active units for the customer app, the counter and the product forms. */
  @GetMapping("/api/v1/units")
  public List<UnitOfMeasure> list() { return units.active(); }

  @GetMapping("/api/v1/admin/units")
  public List<UnitOfMeasure> adminList() { return units.all(); }

  @PostMapping("/api/v1/admin/units")
  @ResponseStatus(HttpStatus.CREATED)
  public UnitOfMeasure create(@Valid @RequestBody Body b) { return units.create(b.toUnit(), CurrentUser.id()); }

  @PutMapping("/api/v1/admin/units/{code}")
  public UnitOfMeasure update(@PathVariable String code, @Valid @RequestBody Body b) {
    return units.update(code, b.toUnit(), CurrentUser.id());
  }

  @DeleteMapping("/api/v1/admin/units/{code}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String code) { units.delete(code, CurrentUser.id()); }
}
