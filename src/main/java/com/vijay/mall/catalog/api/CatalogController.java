package com.vijay.mall.catalog.api;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.Product;
import com.vijay.mall.common.Page;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) { this.catalog = catalog; }

  @GetMapping("/categories")
  public List<Category> categories() { return catalog.categories(); }

  @GetMapping("/products")
  public Page<Product> products(@RequestParam(required = false) UUID categoryId,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "false") boolean hot,
                                @RequestParam(defaultValue = "false") boolean includeOutOfStock,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
    return catalog.list(q, categoryId, hot, includeOutOfStock, page, size);
  }

  @GetMapping("/products/{id}")
  public Product product(@PathVariable UUID id) { return catalog.get(id); }

  /**
   * Products with their variants, one entry per product (a product matches when any variant does). Prefer this to
   * /products, which lists every variant as a separate row. A variant's id is what the cart and orders use.
   */
  @GetMapping("/product-groups")
  public Page<CatalogService.ProductGroup> productGroups(@RequestParam(required = false) UUID categoryId,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(defaultValue = "false") boolean hot,
                                                         @RequestParam(defaultValue = "false") boolean includeOutOfStock,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
    return catalog.groups(q, categoryId, hot, includeOutOfStock, page, size);
  }

  /** One product with all its variants; the id may be the group id or the id of any variant. */
  @GetMapping("/product-groups/{id}")
  public CatalogService.ProductGroup productGroup(@PathVariable UUID id) { return catalog.group(id); }

  @GetMapping("/hot-sale")
  public CatalogService.HotSale hotSale() { return catalog.hotSale(); }
}
