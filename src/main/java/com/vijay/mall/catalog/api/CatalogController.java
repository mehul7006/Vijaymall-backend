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

/**
 * Public (no sign-in) shop endpoints: categories, products, product groups and the hot-sale screen.
 * <p>The GET routes here are opened to guests in the security configuration so people can browse before logging in.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
  private final CatalogService catalog;

  /** Creates the controller with the catalog service. */
  public CatalogController(CatalogService catalog) { this.catalog = catalog; }

  /** Lists the active categories in display order. */
  @GetMapping("/categories")
  public List<Category> categories() { return catalog.categories(); }

  /**
   * Lists products as one row per variant; products with no stock are hidden unless {@code includeOutOfStock} is true.
   * Prefer {@code /product-groups} for the shop screen.
   * @param categoryId optional category filter
   * @param q optional text matched against English and Gujarati names
   * @param hot only products that are on hot sale right now
   * @param includeOutOfStock also return products with no stock
   * @param page zero-based page number
   * @param size page size
   */
  @GetMapping("/products")
  public Page<Product> products(@RequestParam(required = false) UUID categoryId,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "false") boolean hot,
                                @RequestParam(defaultValue = "false") boolean includeOutOfStock,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
    return catalog.list(q, categoryId, hot, includeOutOfStock, page, size);
  }

  /** Returns one product; 404 {@code NOT_FOUND} when it is inactive, counter-only or unknown. */
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

  /** The hot-sale screen: banners that are live now plus up to 20 in-stock hot products. */
  @GetMapping("/hot-sale")
  public CatalogService.HotSale hotSale() { return catalog.hotSale(); }
}
