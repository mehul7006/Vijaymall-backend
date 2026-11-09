package com.vijay.mall.label.api;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.label.app.LabelTemplateService;
import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Label templates. Reading is open to admin and counter staff (they pick a template to print); changing is admin only. */
@RestController
@RequestMapping("/api/v1/admin/label-templates")
public class AdminLabelTemplateController {
  /**
   * Request body of a template. Sizes are millimetres (10 to 200). {@code columns}, {@code rows}, gaps and margins are for SHEET
   * templates only; the sheet is A4.
   */
  public record TemplateBody(String name, @NotNull Kind kind, Double widthMm, Double heightMm, Integer columns, Integer rows, Double gapXMm,
                             Double gapYMm, Double marginLeftMm, Double marginTopMm, BarcodeType barcodeType, @NotNull @Valid LabelFields fields) {
    LabelTemplateService.Input toInput() {
      return new LabelTemplateService.Input(name, kind, widthMm, heightMm, columns, rows, gapXMm, gapYMm, marginLeftMm, marginTopMm, barcodeType, fields);
    }
  }

  private final LabelTemplateService templates;

  /** Creates the controller. */
  public AdminLabelTemplateController(LabelTemplateService templates) { this.templates = templates; }

  /** Lists the templates, the three built-in ones first. */
  @GetMapping
  public Page<LabelTemplate> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
    return templates.list(page, size);
  }

  /** One template; 404 when unknown. */
  @GetMapping("/{id}")
  public LabelTemplate get(@PathVariable UUID id) { return templates.get(id); }

  /** Creates a template (201); admin only. 400 for a bad size, grid or field combination, 409 {@code NAME_TAKEN}. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public LabelTemplate create(@Valid @RequestBody TemplateBody b) { return templates.create(b.toInput(), CurrentUser.id()); }

  /** Replaces a template; admin only. 409 {@code BUILT_IN_TEMPLATE} for the built-in ones. */
  @PutMapping("/{id}")
  public LabelTemplate update(@PathVariable UUID id, @Valid @RequestBody TemplateBody b) { return templates.update(id, b.toInput(), CurrentUser.id()); }

  /** Deletes a template (204); admin only. 409 {@code BUILT_IN_TEMPLATE} for the built-in ones. */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { templates.delete(id, CurrentUser.id()); }
}
