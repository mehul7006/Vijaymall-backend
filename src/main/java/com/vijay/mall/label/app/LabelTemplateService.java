package com.vijay.mall.label.app;

import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.label.domain.LabelData;
import com.vijay.mall.label.domain.LabelFields;
import com.vijay.mall.label.domain.LabelFields.NameLanguage;
import com.vijay.mall.label.domain.LabelLayout;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.infra.LabelTemplateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saved label templates: list, read, create, change and delete, with the size and field rules. */
@Service
public class LabelTemplateService {
  /** Smallest and largest label side, in millimetres. */
  public static final double MIN_MM = 10;
  /** Largest label side, in millimetres. */
  public static final double MAX_MM = 200;
  /** Longest template name. */
  public static final int MAX_NAME = 60;
  /** The font size used when a request leaves it out or sends 0. */
  public static final int DEFAULT_FONT = 7;

  /**
   * What a client sends to create or change a template. Grid and gap values may be left out (null): a sheet then has one cell, and
   * a thermal label ignores them.
   */
  public record Input(String name, Kind kind, Double widthMm, Double heightMm, Integer columns, Integer rows, Double gapXMm,
                      Double gapYMm, Double marginLeftMm, Double marginTopMm, BarcodeType barcodeType, LabelFields fields) {}

  private final LabelTemplateRepository repo;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public LabelTemplateService(LabelTemplateRepository repo, AuditService audit, Clock clock) {
    this.repo = repo;
    this.audit = audit;
    this.clock = clock;
  }

  /** One page of templates, built-in ones first. */
  public Page<LabelTemplate> list(int page, int size) {
    int s = Page.clampSize(size);
    int p = Math.max(0, page);
    return new Page<>(repo.page(p, s), p, s, repo.count());
  }

  /** @throws ApiException 404 when there is no such template */
  public LabelTemplate get(UUID id) { return repo.find(id).orElseThrow(() -> ApiException.notFound("Label template")); }

  /**
   * Creates a template.
   *
   * @throws ApiException 400 for a bad size, grid or field combination, 409 {@code NAME_TAKEN} for a name in use
   */
  @Transactional
  public LabelTemplate create(Input in, UUID actor) {
    LabelTemplate t = validate(in, UUID.randomUUID());
    requireFreeName(t);
    repo.insert(t, clock.instant());
    audit.log(actor, "CREATE", "label_template", t.id(), t.name());
    return get(t.id());
  }

  /**
   * Changes a template. Built-in templates are fixed.
   *
   * @throws ApiException 404 unknown id, 400 invalid, 409 {@code BUILT_IN_TEMPLATE} or {@code NAME_TAKEN}
   */
  @Transactional
  public LabelTemplate update(UUID id, Input in, UUID actor) {
    requireEditable(get(id));
    LabelTemplate t = validate(in, id);
    requireFreeName(t);
    repo.update(t, clock.instant());
    audit.log(actor, "UPDATE", "label_template", id, t.name());
    return get(id);
  }

  /**
   * Deletes a template; past print history keeps the template's name.
   *
   * @throws ApiException 404 unknown id, 409 {@code BUILT_IN_TEMPLATE}
   */
  @Transactional
  public void delete(UUID id, UUID actor) {
    LabelTemplate t = get(id);
    requireEditable(t);
    repo.delete(id);
    audit.log(actor, "DELETE", "label_template", id, t.name());
  }

  private static void requireEditable(LabelTemplate t) {
    if (t.builtIn()) {
      throw ApiException.conflict("BUILT_IN_TEMPLATE", "Built-in templates cannot be changed or deleted; make a copy instead",
          Map.of("templateId", t.id().toString()));
    }
  }

  private void requireFreeName(LabelTemplate t) {
    if (repo.nameTaken(t.name(), t.id())) {
      throw ApiException.conflict("NAME_TAKEN", "A template with this name already exists", Map.of("name", t.name()));
    }
  }

  /** Checks a request and turns it into a template with the given id. */
  LabelTemplate validate(Input in, UUID id) {
    String name = in.name() == null ? "" : in.name().strip();
    if (name.isEmpty() || name.length() > MAX_NAME) throw ApiException.validation("name", "1 to " + MAX_NAME + " characters");
    if (in.kind() == null) throw ApiException.validation("kind", "THERMAL or SHEET");
    double w = size("widthMm", in.widthMm());
    double h = size("heightMm", in.heightMm());
    BarcodeType type = in.barcodeType() == null ? BarcodeType.EAN13 : in.barcodeType();
    if (in.fields() == null) throw ApiException.validation("fields", "required");
    LabelFields f = fields(in.fields());
    LabelTemplate t = in.kind() == Kind.THERMAL ? new LabelTemplate(id, name, Kind.THERMAL, w, h, 1, 1, 0, 0, 0, 0, type, f, false)
        : sheet(id, name, w, h, in, type, f);
    LabelLayout.Layout layout = LabelLayout.layout(t, sample(t));
    if (!layout.fits()) {
      throw ApiException.validation("fields", String.format(Locale.ROOT,
          "these fields need about %.1f mm of height but a label %.0f x %.0f mm has %.1f mm; use fewer fields, a smaller font or a taller label",
          layout.neededMm(), w, h, layout.availableMm()));
    }
    return t;
  }

  private static double size(String field, Double v) {
    if (v == null || v.isNaN() || v < MIN_MM || v > MAX_MM) throw ApiException.validation(field, (int) MIN_MM + " to " + (int) MAX_MM + " mm");
    return v;
  }

  private static LabelFields fields(LabelFields in) {
    int font = in.fontSize() == 0 ? DEFAULT_FONT : in.fontSize();
    if (font < LabelFields.MIN_FONT || font > LabelFields.MAX_FONT) {
      throw ApiException.validation("fields.fontSize", LabelFields.MIN_FONT + " to " + LabelFields.MAX_FONT + " points");
    }
    if (in.pricePerUnit() && !in.price()) throw ApiException.validation("fields.pricePerUnit", "the price per unit needs the selling price (price) to be on");
    return new LabelFields(in.shopName(), in.productName(), in.packSize(), in.mrp(), in.price(), in.packedOn(), in.bestBefore(), in.batchNo(),
        in.pricePerUnit(), font, in.alignment());
  }

  private static LabelTemplate sheet(UUID id, String name, double w, double h, Input in, BarcodeType type, LabelFields f) {
    int columns = in.columns() == null ? 1 : in.columns();
    int rows = in.rows() == null ? 1 : in.rows();
    if (columns < 1 || columns > 10) throw ApiException.validation("columns", "1 to 10");
    if (rows < 1 || rows > 30) throw ApiException.validation("rows", "1 to 30");
    double gapX = range("gapXMm", in.gapXMm(), 50);
    double gapY = range("gapYMm", in.gapYMm(), 50);
    double left = range("marginLeftMm", in.marginLeftMm(), 100);
    double top = range("marginTopMm", in.marginTopMm(), 100);
    if (left + columns * w + (columns - 1) * gapX > LabelTemplate.A4_WIDTH_MM + 1e-6) {
      throw ApiException.validation("columns", "the labels are wider than an A4 page (210 mm)");
    }
    if (top + rows * h + (rows - 1) * gapY > LabelTemplate.A4_HEIGHT_MM + 1e-6) {
      throw ApiException.validation("rows", "the labels are taller than an A4 page (297 mm)");
    }
    return new LabelTemplate(id, name, Kind.SHEET, w, h, columns, rows, gapX, gapY, left, top, type, f, false);
  }

  private static double range(String field, Double v, double max) {
    double value = v == null ? 0 : v;
    if (Double.isNaN(value) || value < 0 || value > max) throw ApiException.validation(field, "0 to " + (int) max + " mm");
    return value;
  }

  /** The fullest label the template can print: every enabled field present. */
  private static LabelData sample(LabelTemplate t) {
    boolean gu = t.fields().productName() != NameLanguage.NONE;
    return new LabelData("Shop", "Product", gu ? "Gu" : "", "500 g", 10_000, 9_000, "Rs 180.00/kg", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 1),
        "B1", "2900000000008");
  }
}
