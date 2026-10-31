package com.vijay.mall.pos.infra;

import com.vijay.mall.pos.domain.PosRecords.ProductPos;
import com.vijay.mall.pos.domain.SoldBy;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

  private static ProductPos toDomain(ProductPosEntity e) {
    return new ProductPos(e.productId, SoldBy.valueOf(e.soldBy), e.plu, e.gstPercent, e.hsn);
  }

  public Optional<ProductPos> find(UUID productId) { return pos.findById(productId).map(PosCatalogRepository::toDomain); }

  public Optional<ProductPos> findByPlu(String plu) { return pos.findByPlu(plu).map(PosCatalogRepository::toDomain); }

  /** Counter attributes for many products; products never configured get the defaults (by piece, 0% GST). */
  public Map<UUID, ProductPos> forProducts(Collection<UUID> ids) {
    if (ids.isEmpty()) return Map.of();
    Map<UUID, ProductPos> found = pos.byIds(ids).stream().map(PosCatalogRepository::toDomain)
        .collect(Collectors.toMap(ProductPos::productId, Function.identity()));
    return ids.stream().distinct().collect(Collectors.toMap(Function.identity(),
        id -> found.getOrDefault(id, ProductPos.defaults(id))));
  }

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

  public Optional<UUID> productByBarcode(String code) { return barcodes.findById(code).map(b -> b.productId); }

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
