package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
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
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Counter attributes of products (GST, how sold, scale code) and their barcodes. */
@Repository
public class PosCatalogRepository {
  private final PosProductJpa pos;
  private final PosBarcodeJpa barcodes;

  PosCatalogRepository(PosProductJpa pos, PosBarcodeJpa barcodes) {
    this.pos = pos;
    this.barcodes = barcodes;
  }

  /** When any product's counter details (price class, GST, barcodes) last changed; null for an empty shop. */
  public Instant lastChange() { return pos.lastChange(); }

  private static ProductPos toDomain(ProductPosEntity e) {
    return new ProductPos(e.productId, SoldBy.valueOf(e.soldBy), e.plu, e.gstPercent, e.hsn);
  }

  /**
   * Products whose counter attributes or barcodes were written at or after the instant. A barcode that was only removed leaves no
   * trace (barcodes have a creation time but no update time), so removing the last barcode alone is not seen here.
   */
  public Set<UUID> changedSince(Instant since) {
    Set<UUID> ids = new LinkedHashSet<>(pos.changedSince(since));
    ids.addAll(barcodes.changedSince(since));
    return ids;
  }

  /** The counter attributes saved for a product, if any. */
  public Optional<ProductPos> find(UUID productId) { return pos.findById(productId).map(PosCatalogRepository::toDomain); }

  /** The product that owns this scale item code (PLU, without leading zeros), if any. */
  public Optional<ProductPos> findByPlu(String plu) { return pos.findByPlu(plu).map(PosCatalogRepository::toDomain); }

  /** Counter attributes for many products; products never configured get the defaults (by piece, 0% GST). */
  public Map<UUID, ProductPos> forProducts(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<UUID, ProductPos> found = pos.byIds(ids).stream().map(PosCatalogRepository::toDomain)
        .collect(Collectors.toMap(ProductPos::productId, Function.identity()));
    return ids.stream().distinct().collect(Collectors.toMap(Function.identity(),
        id -> found.getOrDefault(id, ProductPos.defaults(id))));
  }

  /** Creates or updates the counter attributes of a product. */
  @Transactional
  public void save(ProductPos p, Instant now) {
    ProductPosEntity e = pos.findById(p.productId()).orElseGet(() -> {
      ProductPosEntity n = new ProductPosEntity();
      n.productId = p.productId();
      return n;
    });
    e.soldBy = p.soldBy().name();
    e.plu = p.plu();
    e.gstPercent = p.gstPercent();
    e.hsn = p.hsn() == null ? "" : p.hsn();
    e.updatedAt = now;
    pos.saveAndFlush(e);
  }

  /** The product a barcode belongs to, if the code is known. */
  public Optional<UUID> productByBarcode(String code) { return barcodes.findById(code).map(b -> b.productId); }

  /** The barcodes of a product, in code order. */
  public List<String> barcodesOf(UUID productId) {
    return barcodes.findByProductIdOrderByCode(productId).stream().map(b -> b.code).toList();
  }

  /** Replaces all barcodes of a product. Returns the code that already belongs to another product, if any. */
  @Transactional
  public Optional<String> replaceBarcodes(UUID productId, List<String> codes, Instant now) {
    Optional<String> taken = codes.stream()
        .filter(c -> barcodes.findById(c).filter(b -> !b.productId.equals(productId)).isPresent()).findFirst();
    if (taken.isPresent()) return taken;
    barcodes.deleteForProduct(productId);
    codes.stream().distinct().forEach(c -> {
      ProductBarcodeEntity b = new ProductBarcodeEntity();
      b.code = c;
      b.productId = productId;
      b.createdAt = now;
      barcodes.save(b);
    });
    barcodes.flush();
    return Optional.empty();
  }
}
