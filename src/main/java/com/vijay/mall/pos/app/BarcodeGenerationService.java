package com.vijay.mall.pos.app;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.common.audit.AuditService;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.pos.domain.Gtin;
import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import com.vijay.mall.pos.infra.BarcodeSequenceRepository;
import com.vijay.mall.pos.infra.PosCatalogRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes store-internal EAN-13 barcodes for variants that have none, so a label can be printed and scanned at the counter at once.
 * The code is the in-store prefix, a running number and the check digit. It never equals an existing barcode and never reads as a
 * weighed label of an item code (PLU) that is in use.
 */
@Service
public class BarcodeGenerationService {
  /** How many numbers are tried before giving up (each skipped number was taken by a barcode or looks like a weighed label). */
  static final int MAX_ATTEMPTS = 100;

  /**
   * The outcome of a generate request.
   *
   * @param variantId the variant
   * @param barcode the store-internal barcode of the variant
   * @param created true when this call made it, false when the variant already had one
   * @param barcodes all barcodes of the variant now
   */
  public record Generated(UUID variantId, String barcode, boolean created, List<String> barcodes) {}

  private final CatalogService catalog;
  private final PosCatalogRepository repo;
  private final BarcodeSequenceRepository sequences;
  private final ShopService shop;
  private final BarcodeProps props;
  private final AuditService audit;
  private final Clock clock;

  /** Creates the service. */
  public BarcodeGenerationService(CatalogService catalog, PosCatalogRepository repo, BarcodeSequenceRepository sequences,
                                  ShopService shop, BarcodeProps props, AuditService audit, Clock clock) {
    this.catalog = catalog;
    this.repo = repo;
    this.sequences = sequences;
    this.shop = shop;
    this.props = props;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Returns the variant's store-internal barcode, making and saving one if it has none. Asking again returns the same code.
   *
   * @throws ApiException 404 for an unknown variant, 400 for an item sold by weight (it is found by its item code), 409
   *     {@code BARCODE_RANGE_FULL} when no free number is left
   */
  @Transactional
  public Generated generate(UUID variantId, UUID actor) {
    catalog.adminGet(variantId);
    sequences.lockProduct(variantId);
    ProductPos pos = repo.find(variantId).orElse(ProductPos.defaults(variantId));
    if (pos.soldBy() == SoldBy.WEIGHT) {
      throw ApiException.validation("variantId", "weighed items are found by their item code (PLU), not by a barcode");
    }
    List<String> own = repo.barcodesOf(variantId);
    List<String> prefixes = sequences.prefixes();
    for (String code : own) {
      if (isStoreGenerated(code, prefixes)) return new Generated(variantId, code, false, own);
    }
    String code = allocate(variantId);
    audit.log(actor, "CREATE", "product_barcode", variantId, "generated " + code);
    return new Generated(variantId, code, true, repo.barcodesOf(variantId));
  }

  /** True for a valid EAN-13 that starts with any in-store prefix that was ever used. */
  static boolean isStoreGenerated(String code, List<String> prefixes) {
    return code.length() == 13 && Gtin.isValid(code) && prefixes.stream().anyMatch(code::startsWith);
  }

  private String allocate(UUID variantId) {
    String prefix = props.storePrefix();
    int width = 12 - prefix.length();
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      long n = sequences.next(prefix);
      String digits = Long.toString(n);
      if (digits.length() > width) break;
      String code = Gtin.withCheckDigit(prefix + "0".repeat(width - digits.length()) + digits);
      if (looksLikeWeighedLabelInUse(code)) continue;
      if (repo.addBarcode(variantId, code, clock.instant())) return code;
    }
    throw ApiException.conflict("BARCODE_RANGE_FULL", "No free store barcode number is left for prefix " + prefix, Map.of("prefix", prefix));
  }

  /** A 13-digit code starting with the scale prefix is read as a weighed label, so it must not carry an item code that exists. */
  private boolean looksLikeWeighedLabelInUse(String code) {
    String scale = shop.get().weighedPrefix();
    if (scale.isEmpty() || !code.startsWith(scale)) return false;
    return repo.findByPlu(PosCatalogService.normalizePlu(code.substring(scale.length(), 7))).isPresent();
  }
}
