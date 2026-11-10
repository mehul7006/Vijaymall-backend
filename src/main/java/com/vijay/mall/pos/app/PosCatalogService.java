package com.vijay.mall.pos.app;

import java.math.BigDecimal;
import com.vijay.mall.pos.domain.GstRates;
import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.ProductData;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.inventory.app.InventoryService;
import com.vijay.mall.inventory.domain.Lot;
import com.vijay.mall.inventory.domain.LotInfo;
import com.vijay.mall.pos.app.PosViews.PosAttributes;
import com.vijay.mall.pos.app.PosViews.PosProduct;
import com.vijay.mall.pos.app.PosViews.Scan;
import com.vijay.mall.pos.domain.BarcodeParser;
import com.vijay.mall.pos.domain.DisplayNames;
import com.vijay.mall.pos.domain.Gtin;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.PosRecords.ShopSetting;
import com.vijay.mall.pos.domain.ScannedCode;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.domain.VariableMeasureBarcode;
import com.vijay.mall.pos.domain.WeighedMode;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finds products at the counter (scan or search) and keeps their counter attributes and barcodes. */
@Service
public class PosCatalogService {
  private final CatalogService catalog;
  private final PosCatalogRepository repo;
  private final ShopService shop;
  private final InventoryService inventory;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public PosCatalogService(CatalogService catalog, PosCatalogRepository repo, ShopService shop, InventoryService inventory,
                           AuditService audit, Clock clock) {
    this.catalog = catalog;
    this.repo = repo;
    this.shop = shop;
    this.inventory = inventory;
    this.audit = audit;
    this.clock = clock;
  }

  private VariableMeasureBarcode variableMeasure;

  /** Optional (plain unit tests run without it): the weighing-scale barcode reader, which only reads when its setting is on. */
  @Autowired(required = false)
  void setVariableMeasure(VariableMeasureBarcode variableMeasure) { this.variableMeasure = variableMeasure; }

  // ---------- counter ----------
  /** Resolves a scanned code. Unknown codes and inactive products are 404 so the cashier can search by name instead. */
  public Scan scan(String raw) {
    ShopSetting s = shop.get();
    ScannedCode code = decode(raw, s);
    return switch (code) {
      case ScannedCode.Plain p -> {
        UUID id = repo.productByBarcode(p.code()).orElseThrow(() -> ApiException.notFound("Barcode"));
        PosProduct product = one(id);
        if (product.soldBy() == SoldBy.WEIGHT) throw ApiException.validation("code", "weighed item: enter the weight");
        yield new Scan(product, 1, product.soldBy().lineAmount(product.pricePaise(), 1),
            inventory.lotInfo(id, product.pricePaise(), product.mrpPaise()).orElse(null));
      }
      case ScannedCode.Weighed w -> {
        ProductPos pos = repo.findByPlu(normalizePlu(w.plu())).orElseThrow(() -> ApiException.notFound("Item code"));
        PosProduct product = one(pos.productId());
        if (product.soldBy() != SoldBy.WEIGHT) throw ApiException.validation("code", "this item code is not a weighed item");
        long grams = w.mode() == WeighedMode.WEIGHT_GRAMS ? w.value() : product.soldBy().qtyForAmount(product.pricePaise(), w.value());
        if (grams <= 0 || grams > 100_000) throw ApiException.validation("code", "weight on the label is out of range");
        yield new Scan(product, (int) grams, product.soldBy().lineAmount(product.pricePaise(), (int) grams),
            inventory.lotInfo(pos.productId(), product.pricePaise(), product.mrpPaise()).orElse(null));
      }
    };
  }

  /**
   * Reads a scanned string. A code that is a saved barcode is always an ordinary pack code, even when it starts with the scale
   * prefix (store-generated barcodes may). The switched-on variable-measure reader is tried before the shop's weighed prefix.
   */
  private ScannedCode decode(String raw, ShopSetting s) {
    String text = raw == null ? "" : raw.strip();
    if (variableMeasure != null && variableMeasure.enabled()) {
      Optional<VariableMeasureBarcode.Reading> reading = variableMeasure.parse(text);
      if (reading.isPresent()) return new ScannedCode.Weighed(reading.get().itemCode(), reading.get().value(), s.weighedMode());
    }
    boolean scaleShaped = !s.weighedPrefix().isEmpty() && text.matches("\\d{13}") && text.startsWith(s.weighedPrefix());
    if (scaleShaped && repo.productByBarcode(text).isPresent()) return new ScannedCode.Plain(text);
    try {
      return BarcodeParser.parse(text, s.weighedPrefix(), s.weighedMode())
          .orElseThrow(() -> ApiException.validation("code", "code is empty"));
    } catch (IllegalArgumentException e) {
      throw ApiException.validation("code", e.getMessage());
    }
  }

  /**
   * A version stamp for the product list as the counter sees it: the time of the latest change to any product or its counter
   * details. An offline till keeps its own copy and downloads again when this is newer than the stamp it saved.
   */
  public Instant catalogVersion() {
    Instant a = catalog.lastChange();
    Instant b = repo.lastChange();
    if (a == null) return b == null ? Instant.EPOCH : b;
    return b != null && b.isAfter(a) ? b : a;
  }

  /**
   * Searches counter products by name, with price, GST, barcodes and stock on hand.
   *
   * @param page zero-based page number
   * @param size page size
   */
  public Page<PosProduct> search(String q, int page, int size) { return search(q, page, size, null); }

  /**
   * Searches counter products. {@code q} matches names, and also any barcode or the item code (PLU) exactly; a product with
   * an exact barcode or code match is listed first. With {@code updatedSince} only products whose data changed at or after
   * that instant are returned: the product itself, its counter attributes or its barcodes (a removed barcode alone is not
   * seen, because only creation times are kept for barcodes).
   *
   * @param page zero-based page number
   * @param size page size
   * @param updatedSince keep only products changed at or after this instant (null = all)
   */
  public Page<PosProduct> search(String q, int page, int size, Instant updatedSince) {
    if (q != null && q.length() > 100) throw ApiException.validation("q", "must be at most 100 characters");
    Set<UUID> exact = exactMatches(q);
    Page<ProductData> found = exact.isEmpty() && updatedSince == null ? catalog.posSearch(q, page, size)
        : catalog.posSearch(q, exact, updatedSince, page, size);
    return found.withItems(views(found.items()));
  }

  /** Products that own the typed text as a barcode or as an item code (PLU, leading zeros ignored). */
  private Set<UUID> exactMatches(String q) {
    Set<UUID> ids = new LinkedHashSet<>();
    if (q == null || q.isBlank()) return ids;
    String t = q.strip();
    repo.productByBarcode(t).ifPresent(ids::add);
    if (t.matches("\\d{1,6}")) repo.findByPlu(normalizePlu(t)).map(ProductPos::productId).ifPresent(ids::add);
    return ids;
  }

  private PosProduct one(UUID id) {
    List<ProductData> d = catalog.posProducts(List.of(id)).stream().filter(ProductData::active).toList();
    if (d.isEmpty()) throw ApiException.notFound("Product");
    return views(d).get(0);
  }

  private List<PosProduct> views(Collection<ProductData> items) {
    List<UUID> ids = items.stream().map(ProductData::id).toList();
    Map<UUID, ProductPos> pos = repo.forProducts(ids);
    var stocks = inventory.stocks(ids);
    Map<UUID, String> variants = catalog.posVariantNames(ids);
    Map<UUID, List<Lot>> lotPriced = inventory.pricedLots(ids);
    return items.stream().map(p -> {
      ProductPos a = pos.get(p.id());
      String variant = variants.getOrDefault(p.id(), "");
      String pack = p.packSize() == null ? "" : p.packSize();
      return new PosProduct(p.id(), p.nameEn(), p.nameGu(), p.pricePaise(), p.mrpPaise(), a.soldBy(), a.gstPercent(), a.hsn(),
          a.plu(), a.soldBy() == SoldBy.WEIGHT, stocks.get(p.id()).qty(), p.imageUrl(), p.coinRedeemable(), p.earnsCoins(),
          repo.barcodesOf(p.id()), pack, p.unit() == null ? "" : p.unit(), variant, DisplayNames.of(p.nameEn(), variant, pack),
          LotInfo.of(lotPriced.getOrDefault(p.id(), List.of()), true, p.pricePaise(), p.mrpPaise()).orElse(null));
    }).toList();
  }

  // ---------- admin ----------
  /**
   * The counter attributes of a product (how it is sold, PLU, GST, HSN, barcodes); defaults when none were saved.
   *
   * @throws ApiException 404 if the product does not exist
   */
  public PosAttributes attributes(UUID productId) {
    catalog.adminGet(productId);
    ProductPos p = repo.find(productId).orElse(ProductPos.defaults(productId));
    return new PosAttributes(productId, p.soldBy(), p.plu(), p.gstPercent(), p.hsn(), p.soldBy() == SoldBy.WEIGHT,
        repo.barcodesOf(productId));
  }

  /**
   * Saves counter attributes and replaces the barcodes. Weighed items become counter-only.
   *
   * <p>{@code gstPercent} and {@code hsn} are optional and follow one rule: a value that is left out, null (or, for the HSN, blank) keeps
   * what is stored; an explicit value wins. When nothing is stored yet (no counter attributes: rate 0 and no HSN) the defaults 0 and
   * an empty HSN apply. An untouched field of an edit form therefore never wipes a saved rate or HSN. The rate must be one of
   * {@link GstRates#ALLOWED}.
   */
  @Transactional
  public PosAttributes save(UUID productId, SoldBy soldBy, String plu, BigDecimal gstPercentIn, String hsn, List<String> barcodes,
                            UUID actor) {
    catalog.adminGet(productId);
    ProductPos stored = repo.find(productId).orElse(ProductPos.defaults(productId));
    BigDecimal gstPercent = gstPercentIn == null ? stored.gstPercent() : GstRates.require(gstPercentIn, "gstPercent");
    String hsnToSave = hsn == null || hsn.isBlank() ? stored.hsn() : hsn.strip();
    String code = plu == null || plu.isBlank() ? null : plu.trim();
    boolean digits = code != null && code.matches("\\d{1,6}");
    if (soldBy == SoldBy.WEIGHT && (code == null || !digits)) {
      throw ApiException.validation("plu", "weighed items need a numeric item code (PLU)");
    }
    if (code != null && !digits) throw ApiException.validation("plu", "digits only, up to 6");
    if (code != null) code = normalizePlu(code);
    List<String> codes = barcodes == null ? List.of() : barcodes.stream().map(String::strip).filter(c -> !c.isEmpty()).distinct().toList();
    if (codes.stream().anyMatch(c -> !c.matches("[A-Za-z0-9\\-]{4,32}"))) {
      throw ApiException.validation("barcodes", "4 to 32 letters, digits or dashes");
    }
    codes.stream().map(Gtin::checkDigitProblem).flatMap(Optional::stream).findFirst().ifPresent(problem -> {
      throw ApiException.validation("barcodes", problem);
    });
    if (soldBy == SoldBy.WEIGHT && codes.stream().anyMatch(c -> c.length() == 13 && c.startsWith(shop.get().weighedPrefix()))) {
      throw ApiException.validation("barcodes", "a code starting with the scale prefix would be read as a weighed label");
    }
    Optional<ProductPos> samePlu = code == null ? Optional.empty() : repo.findByPlu(code).filter(x -> !x.productId().equals(productId));
    if (samePlu.isPresent()) throw ApiException.conflict("PLU_TAKEN", "Item code already used by another product", Map.of("plu", code));
    repo.save(new ProductPos(productId, soldBy, code, gstPercent, hsnToSave == null ? "" : hsnToSave), clock.instant());
    repo.replaceBarcodes(productId, codes, clock.instant()).ifPresent(taken -> {
      throw ApiException.conflict("BARCODE_TAKEN", "Barcode already belongs to another product", Map.of("barcode", taken));
    });
    catalog.setPosOnly(productId, soldBy == SoldBy.WEIGHT);
    audit.log(actor, "UPDATE", "product_pos", productId, soldBy + " gst " + GstRates.plain(gstPercent) + " barcodes " + codes.size());
    return attributes(productId);
  }

  /** Scale labels pad the item code with zeros; the shop types it without them. Both sides are compared without. */
  static String normalizePlu(String digits) { return digits.replaceFirst("^0+(?!$)", ""); }

  /** The barcodes of one product, in code order; empty when it has none. */
  public List<String> barcodesOf(UUID productId) { return repo.barcodesOf(productId); }

  /** Product lookups by id for pricing. */
  public Map<UUID, ProductPos> attributesFor(Collection<UUID> ids) { return repo.forProducts(ids); }

  Map<UUID, ProductData> productsById(Collection<UUID> ids) {
    return catalog.posProducts(ids).stream().collect(Collectors.toMap(ProductData::id, Function.identity()));
  }
}
