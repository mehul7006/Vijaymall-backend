package com.vijay.mall.label.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.config.RuntimeSettings;
import com.vijay.mall.config.Settings;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.infra.InventoryRepository;
import com.vijay.mall.label.domain.LabelData;
import com.vijay.mall.label.domain.LabelElement;
import com.vijay.mall.label.domain.LabelLayout;
import com.vijay.mall.label.domain.LabelTemplate;
import com.vijay.mall.label.domain.LabelTemplate.BarcodeType;
import com.vijay.mall.label.domain.LabelTemplate.Kind;
import com.vijay.mall.label.domain.PackSize;
import com.vijay.mall.label.domain.PrintJob;
import com.vijay.mall.label.domain.SheetLayout;
import com.vijay.mall.label.infra.LabelHistoryRepository;
import com.vijay.mall.label.render.EscPosRenderer;
import com.vijay.mall.label.render.GujaratiRenderer;
import com.vijay.mall.label.render.PdfLabelRenderer;
import com.vijay.mall.label.render.TsplRenderer;
import com.vijay.mall.label.render.ZplRenderer;
import com.vijay.mall.pos.app.BarcodeGenerationService;
import com.vijay.mall.pos.app.PosCatalogService;
import com.vijay.mall.pos.app.ShopService;
import com.vijay.mall.pos.domain.DisplayNames;
import com.vijay.mall.pos.domain.Gtin;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prints barcode labels: gathers the data of each variant, gives a variant without a barcode a store-internal one, lays the
 * labels out with the template and renders them as PDF, printer language (TSPL, ZPL, ESC/POS) or a JSON description. Every print is
 * logged in the history and the audit log.
 */
@Service
public class LabelService {
  /** Most labels of one variant in a print. */
  public static final int MAX_COPIES = 500;
  /** Most labels in one print request. */
  public static final int MAX_LABELS = 2000;
  /** Most different variants in one print request. */
  public static final int MAX_ITEMS = 200;
  /** The word that asks for one label per unit in stock. */
  public static final String FROM_STOCK = "fromStock";

  /** The output format. */
  public enum Mode {
    PDF("application/pdf", "labels.pdf"), TSPL("text/plain;charset=ISO-8859-1", "labels.tspl"), ZPL("text/plain;charset=US-ASCII", "labels.zpl"),
    ESCPOS("application/octet-stream", "labels.escpos"), JSON("application/json", "labels.json");

    private final String contentType;
    private final String filename;

    Mode(String contentType, String filename) {
      this.contentType = contentType;
      this.filename = filename;
    }

    /** The HTTP content type of the output. */
    public String contentType() { return contentType; }

    /** The suggested file name. */
    public String filename() { return filename; }
  }

  /**
   * One variant to print. {@code copies} is a whole number 1 to 500 or the word {@code fromStock}.
   */
  @Schema(name = "LabelPrintItem")
  public record Item(UUID variantId, Object copies) {}

  /**
   * A print request. {@code startPosition} (1-based) is only for SHEET templates. The dates and batch are printed on every label
   * of the request when the template has those fields.
   */
  public record Request(UUID templateId, List<Item> items, Mode mode, Integer startPosition, LocalDate packedOn, LocalDate bestBefore,
                        String batchNo) {}

  /**
   * The answer: {@code bytes} for PDF and the printer languages, {@code json} for the JSON mode (the other is null).
   *
   * @param labels how many labels were printed
   * @param pages how many pages (sheets) the PDF or JSON has; the number of labels for thermal templates
   */
  public record Printed(Mode mode, byte[] bytes, Map<String, Object> json, int labels, int pages) {}

  private final LabelTemplateService templates;
  private final CatalogService catalog;
  private final PosCatalogService posCatalog;
  private final BarcodeGenerationService generation;
  private final InventoryService inventory;
  private final ShopService shop;
  private final LabelLotSource lots;
  private final LabelHistoryRepository history;
  private final RuntimeSettings settings;
  private final GujaratiRenderer gujarati;
  private final PdfLabelRenderer pdf;
  private final TsplRenderer tspl;
  private final ZplRenderer zpl;
  private final EscPosRenderer escPos;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public LabelService(LabelTemplateService templates, CatalogService catalog, PosCatalogService posCatalog, BarcodeGenerationService generation,
                      InventoryService inventory, ShopService shop, LabelLotSource lots, LabelHistoryRepository history, RuntimeSettings settings,
                      GujaratiRenderer gujarati, PdfLabelRenderer pdf, TsplRenderer tspl, ZplRenderer zpl, EscPosRenderer escPos,
                      AuditService audit, Clock clock) {
    this.templates = templates;
    this.catalog = catalog;
    this.posCatalog = posCatalog;
    this.generation = generation;
    this.inventory = inventory;
    this.shop = shop;
    this.lots = lots;
    this.history = history;
    this.settings = settings;
    this.gujarati = gujarati;
    this.pdf = pdf;
    this.tspl = tspl;
    this.zpl = zpl;
    this.escPos = escPos;
    this.audit = audit;
    this.clock = clock;
  }

  /** The print history, newest first; {@code variantId} may be null for all variants. */
  public Page<LabelHistoryRepository.Row> history(UUID variantId, int page, int size) {
    return history.page(variantId, Math.max(0, page), Page.clampSize(size));
  }

  /**
   * Prints labels. A variant without a barcode gets a store-internal one first (so the label scans at the counter).
   *
   * @throws ApiException 404 for an unknown template or variant; 400 for a bad quantity, more than 2000 labels, a start position
   *     outside the sheet, a mode the template cannot use, a label that does not fit, an item sold by weight, or Gujarati names
   *     when the server cannot draw them
   */
  @Transactional
  public Printed print(Request req, UUID actor) {
    LabelTemplate t = templates.get(req.templateId());
    if (req.mode() == null) throw ApiException.validation("mode", "PDF, TSPL, ZPL, ESCPOS or JSON");
    if (t.kind() == Kind.SHEET && req.mode() != Mode.PDF && req.mode() != Mode.JSON) {
      throw ApiException.validation("mode", "sheet templates print as PDF or JSON; thermal printer languages need a THERMAL template");
    }
    int start = startPosition(t, req.startPosition());
    List<Item> items = req.items();
    if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) throw ApiException.validation("items", "1 to " + MAX_ITEMS + " variants");
    if (req.packedOn() != null && req.bestBefore() != null && req.bestBefore().isBefore(req.packedOn())) {
      throw ApiException.validation("bestBefore", "must not be before the packing date");
    }
    List<UUID> ids = items.stream().map(Item::variantId).distinct().toList();
    Map<UUID, ProductData> products = catalog.posProducts(ids).stream().collect(Collectors.toMap(ProductData::id, Function.identity()));
    for (int i = 0; i < items.size(); i++) {
      if (!products.containsKey(items.get(i).variantId())) throw ApiException.validation("items[" + i + "].variantId", "unknown variant");
    }
    List<Integer> copies = copies(items, ids);
    int total = copies.stream().mapToInt(Integer::intValue).sum();
    if (total > MAX_LABELS) throw ApiException.validation("items", "at most " + MAX_LABELS + " labels per print, this asks for " + total);
    PrintJob job = new PrintJob(t, settings.getInt(Settings.LABEL_DPI), start, entries(t, req, items, products, copies, actor));
    guardGujarati(job, req.mode());
    Printed printed = render(job, req.mode());
    record(job, req.mode(), actor);
    return printed;
  }

  private static int startPosition(LabelTemplate t, Integer given) {
    int start = given == null ? 1 : given;
    if (t.kind() == Kind.THERMAL && start != 1) throw ApiException.validation("startPosition", "only sheet templates have a start position");
    if (start < 1 || start > t.perPage()) throw ApiException.validation("startPosition", "1 to " + t.perPage());
    return start;
  }

  /** The label count of each item, in request order. */
  private List<Integer> copies(List<Item> items, List<UUID> ids) {
    Map<UUID, InventoryRepository.Stock> stocks = items.stream().anyMatch(i -> isFromStock(i.copies())) ? inventory.stocks(ids) : Map.of();
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      Item item = items.get(i);
      String field = "items[" + i + "].copies";
      int n;
      if (isFromStock(item.copies())) {
        int stock = stocks.containsKey(item.variantId()) ? stocks.get(item.variantId()).qty() : 0;
        if (stock <= 0) throw ApiException.validation(field, "there is no stock to print labels for");
        n = Math.min(stock, MAX_COPIES);
      } else if (item.copies() instanceof Integer c && c >= 1 && c <= MAX_COPIES) {
        n = c;
      } else {
        throw ApiException.validation(field, "a whole number from 1 to " + MAX_COPIES + " or \"" + FROM_STOCK + "\"");
      }
      out.add(n);
    }
    return out;
  }

  private static boolean isFromStock(Object copies) { return copies instanceof String s && FROM_STOCK.equalsIgnoreCase(s.strip()); }

  private List<PrintJob.Entry> entries(LabelTemplate t, Request req, List<Item> items, Map<UUID, ProductData> products, List<Integer> counts,
                                       UUID actor) {
    List<UUID> ids = items.stream().map(Item::variantId).distinct().toList();
    Map<UUID, String> variantNames = catalog.posVariantNames(ids);
    Map<UUID, ProductPos> attributes = posCatalog.attributesFor(ids);
    String shopName = shop.get().shopName();
    List<PrintJob.Entry> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      ProductData p = products.get(items.get(i).variantId());
      ProductPos pos = attributes.get(p.id());
      if (pos != null && pos.soldBy() == SoldBy.WEIGHT) {
        throw ApiException.validation("items[" + i + "].variantId", p.nameEn() + " is sold by weight and has no pack barcode to print");
      }
      String barcode = barcodeFor(t.barcodeType(), p.id(), actor);
      String variant = variantNames.getOrDefault(p.id(), "");
      LabelData data = data(req, p, shopName, DisplayNames.of(p.nameEn(), variant, ""), barcode);
      LabelLayout.Layout layout = LabelLayout.layout(t, data);
      if (!layout.fits()) {
        throw ApiException.validation("templateId", "the label for " + p.nameEn() + " does not fit the template: it needs " + Math.round(layout.neededMm())
            + " mm of height and has " + Math.round(layout.availableMm()) + " mm");
      }
      out.add(new PrintJob.Entry(p.id(), DisplayNames.of(p.nameEn(), variant, p.packSize()), counts.get(i), layout.elements(), t.barcodeType(), barcode));
    }
    return out;
  }

  private LabelData data(Request req, ProductData p, String shopName, String name, String barcode) {
    LabelLotSource.LotInfo lot = lots.lotFor(p.id()).orElse(new LabelLotSource.LotInfo(null, null, null));
    String batch = req.batchNo() == null || req.batchNo().isBlank() ? lot.batchNo() : req.batchNo().strip();
    return new LabelData(shopName, name, p.nameGu(), p.packSize(), p.mrpPaise(), p.pricePaise(),
        PackSize.pricePerUnit(p.packSize(), p.pricePaise()).orElse(null), req.packedOn() == null ? lot.packedOn() : req.packedOn(),
        req.bestBefore() == null ? lot.bestBefore() : req.bestBefore(), batch, barcode);
  }

  /**
   * The code to print: for EAN-13 the variant's own valid EAN-13, for Code 128 and QR its first barcode. A variant without a fitting
   * barcode gets a store-internal EAN-13 (which also fits the other two symbologies).
   */
  private String barcodeFor(BarcodeType type, UUID variantId, UUID actor) {
    List<String> own = posCatalog.barcodesOf(variantId);
    for (String code : own) {
      if (type != BarcodeType.EAN13 || code.length() == 13 && Gtin.isValid(code)) return code;
    }
    return generation.generate(variantId, actor).barcode();
  }

  private void guardGujarati(PrintJob job, Mode mode) {
    if (mode == Mode.JSON || gujarati.available()) return;
    boolean needed = job.entries().stream().flatMap(e -> e.elements().stream())
        .anyMatch(e -> e instanceof LabelElement.Text t && t.gujarati());
    if (!needed) return;
    throw ApiException.validation("mode", mode == Mode.PDF ? "Gujarati text cannot be drawn on this server; use JSON mode and let the app draw it"
        : "Gujarati names need PDF or image mode");
  }

  private Printed render(PrintJob job, Mode mode) {
    int labels = job.totalLabels();
    boolean sheet = job.template().kind() == Kind.SHEET;
    int pages = sheet ? SheetLayout.pages(job.template(), job.startPosition(), labels) : labels;
    return switch (mode) {
      case PDF -> new Printed(mode, pdf.render(job), null, labels, pages);
      case TSPL -> new Printed(mode, tspl.render(job), null, labels, pages);
      case ZPL -> new Printed(mode, zpl.render(job), null, labels, pages);
      case ESCPOS -> new Printed(mode, escPos.render(job), null, labels, pages);
      case JSON -> new Printed(mode, null, LabelJson.of(job), labels, pages);
    };
  }

  private void record(PrintJob job, Mode mode, UUID actor) {
    var now = clock.instant();
    for (PrintJob.Entry e : job.entries()) {
      history.insert(new LabelHistoryRepository.Row(UUID.randomUUID(), now, actor, null, job.template().id(), job.template().name(), e.variantId(),
          e.name(), e.copies(), mode.name()));
    }
    audit.log(actor, "PRINT", "label", job.template().id(), mode + " " + job.totalLabels() + " labels for " + job.entries().size() + " variants");
  }
}
