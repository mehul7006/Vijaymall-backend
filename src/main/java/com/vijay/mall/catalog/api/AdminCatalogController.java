package com.vijay.mall.catalog.api;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.app.ImageStore;
import com.vijay.mall.catalog.app.ProductPatch;
import com.vijay.mall.catalog.domain.AdminCategory;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.HotBanner;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.idem.AdminOps;
import com.vijay.mall.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Admin REST endpoints for managing the catalog: products and their variants, stock adjustments, product images,
 * categories and hot-sale banners.
 * <p>Everything under {@code /api/v1/admin} is restricted to the ADMIN role by the security configuration. Calls that are not
 * naturally repeat-safe (create product, create category, stock adjustment) accept an optional {@code Idempotency-Key} header
 * so a double tap applies the action once.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {
  private AdminOps ops;

  /** Makes repeats of an admin action with the same Idempotency-Key harmless (plain unit tests build the controller without it). */
  @Autowired
  void setOps(AdminOps ops) { this.ops = ops; }

  /**
   * Request body to create or fully update a single product (one sellable variant).
   * <p>Amounts are integer paise. {@code qtyOnHand} and {@code lowThreshold} left out mean "keep the current value" on an edit;
   * a new product then starts with 0 stock and the default alert level of 5. {@code variantName} / {@code variantOrder} are
   * applied after the save when given.
   */
  public record ProductBody(@NotNull UUID categoryId, @NotBlank String nameEn, @NotBlank String nameGu,
                            String description, @Min(0) long mrpPaise, @Min(0) long pricePaise, String unit,
                            String packSize, Boolean active, Boolean hotSale, Instant hotStart, Instant hotEnd,
                            Boolean earnsCoins, Boolean coinRedeemable, @Min(0) Integer qtyOnHand, @Min(0) Integer lowThreshold,
                            UUID productGroupId, String variantName, Integer variantOrder) {
    CatalogService.ProductInput toInput() {
      return new CatalogService.ProductInput(categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit,
          packSize, active == null || active, Boolean.TRUE.equals(hotSale), hotStart, hotEnd,
          earnsCoins == null || earnsCoins, coinRedeemable == null || coinRedeemable,
          // left out (-1) means "keep what is there" on an edit; a new product then starts at 0 stock and the default alert level of 5
          qtyOnHand == null ? -1 : qtyOnHand, lowThreshold == null ? -1 : lowThreshold, productGroupId);
    }
  }
  /** A new variant (size, flavour, colour) of an existing product; name, description and category are copied from it. */
  public record VariantBody(String variantName, String packSize, String unit, @Min(0) long mrpPaise, @Min(0) long pricePaise,
                            Boolean active, Integer qtyOnHand, Integer lowThreshold, Integer variantOrder) {
    CatalogService.VariantInput toInput() {
      return new CatalogService.VariantInput(variantName, packSize, unit, mrpPaise, pricePaise, active,
          qtyOnHand == null ? 0 : qtyOnHand, lowThreshold == null ? -1 : lowThreshold, variantOrder);
    }
  }

  /** What every variant of a product shares. */
  public record GroupBody(@NotNull UUID categoryId, @NotBlank String nameEn, @NotBlank String nameGu, String description,
                          Boolean hotSale, Instant hotStart, Instant hotEnd, Boolean earnsCoins, Boolean coinRedeemable,
                          Boolean active) {
    CatalogService.GroupInput toInput() {
      return new CatalogService.GroupInput(categoryId, nameEn, nameGu, description, hotSale, hotStart, hotEnd, earnsCoins,
          coinRedeemable, active);
    }
  }

  /**
   * Request body for a manual stock change: {@code delta} is relative (positive adds, negative removes) and {@code reason} is
   * {@code ADJUST} for a correction, anything else is recorded as a restock.
   */
  public record StockBody(int delta, String reason) {}
  /**
   * Request body to create or update a category; a missing {@code sortOrder} means 0 and a missing {@code active} means active.
   */
  public record CategoryBody(@NotBlank String nameEn, @NotBlank String nameGu, Integer sortOrder, Boolean active) {}
  /**
   * Request body to create or update a hot-sale banner; a missing {@code active} means active and a null start or end means no limit.
   */
  public record BannerBody(@NotBlank String title, String subtitle, String imageUrl, Instant startAt, Instant endAt,
                           Boolean active) {}

  private final CatalogService catalog;
  private final ImageStore images;

  /** Creates the controller with the catalog service and the image store used for product pictures. */
  public AdminCatalogController(CatalogService catalog, ImageStore images) {
    this.catalog = catalog;
    this.images = images;
  }

  /**
   * Lists products for the admin screen, including inactive and counter-only ones, with stock numbers.
   * @param q optional text matched against the English and Gujarati names
   * @param categoryId optional category filter
   * @param page zero-based page number
   * @param size page size (clamped by the service)
   */
  @GetMapping("/products")
  public Page<AdminProduct> list(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) UUID categoryId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
    return catalog.adminList(q, categoryId, page, size);
  }

  /**
   * Creates a product with its opening stock; answers 201 Created.
   * <p>Repeating the call with the same {@code Idempotency-Key} returns the product created the first time instead of a second one.
   * Validation errors: negative price, or MRP below the price.
   */
  @PostMapping("/products")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminProduct create(@Valid @RequestBody ProductBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return AdminOps.run(ops, key, "product-create", CurrentUser.id(), AdminOps.fingerprint(b), () -> {
      AdminProduct created = catalog.create(b.toInput(), CurrentUser.id());
      return b.variantName() == null && b.variantOrder() == null ? created
          : catalog.setVariant(created.id(), b.variantName(), b.variantOrder(), CurrentUser.id());
    }, p -> p.id().toString(), ref -> catalog.adminGet(UUID.fromString(ref)));
  }

  /** Returns one product with its stock numbers; 404 {@code NOT_FOUND} when the id is unknown. */
  @GetMapping("/products/{id}")
  public AdminProduct get(@PathVariable UUID id) { return catalog.adminGet(id); }

  /**
   * Updates a product's details, stock level and alert threshold.
   * <p>A changed {@code qtyOnHand} is recorded as an {@code ADJUST} stock movement. Same price validation as creating.
   */
  @PutMapping("/products/{id}")
  public AdminProduct update(@PathVariable UUID id, @Valid @RequestBody ProductBody b) {
    AdminProduct updated = catalog.update(id, b.toInput(), CurrentUser.id());
    return b.variantName() == null && b.variantOrder() == null ? updated
        : catalog.setVariant(id, b.variantName(), b.variantOrder(), CurrentUser.id());
  }

  /**
   * Changes only the fields present in the JSON body; everything else (including stock) stays as it is.
   * <p>Accepted keys are those of the PUT body except {@code qtyOnHand} (a 400: use the stock endpoint) and {@code productGroupId}.
   * An explicit {@code null} clears {@code hotStart}, {@code hotEnd} and {@code description}. Same price and window validation as PUT.
   */
  @PatchMapping("/products/{id}")
  public AdminProduct patch(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
    return catalog.patch(id, new ProductPatch(body), CurrentUser.id());
  }

  /**
   * Deletes a product that was never sold, received or reviewed; otherwise switches it off instead.
   * <p>Always answers 200 with {@code deleted}, {@code deactivated} and a {@code reason}; 404 for an unknown id.
   */
  @DeleteMapping("/products/{id}")
  public CatalogService.DeleteResult delete(@PathVariable UUID id) { return catalog.deleteProduct(id, CurrentUser.id()); }

  /** Every variant of the product this id belongs to, including inactive ones, with stock. */
  @GetMapping("/products/{id}/variants")
  public List<AdminProduct> variants(@PathVariable UUID id) { return catalog.adminVariants(id); }

  /** Adds a variant (another size, flavour or colour) to this product. */
  @PostMapping("/products/{id}/variants")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminProduct addVariant(@PathVariable UUID id, @Valid @RequestBody VariantBody b) {
    return catalog.addVariant(id, b.toInput(), CurrentUser.id());
  }

  /** Edits the name, description, category and flags that all variants of a product share; leaving {@code active} out keeps the current value. */
  @PutMapping("/product-groups/{id}")
  public List<AdminProduct> updateGroup(@PathVariable UUID id, @Valid @RequestBody GroupBody b) {
    return catalog.updateGroup(id, b.toInput(), CurrentUser.id());
  }

  /**
   * Adds or removes stock by a relative amount and records a stock movement.
   * <p>Because the change is relative, send an {@code Idempotency-Key} so that a double tap on "+50" is applied only once.
   */
  @PostMapping("/products/{id}/stock")
  public AdminProduct stock(@PathVariable UUID id, @RequestBody StockBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    // a delta is relative, so a double tap on "+50" must not add 100: the same key applies it once
    return AdminOps.run(ops, key, "stock-adjust:" + id, CurrentUser.id(), AdminOps.fingerprint(id, b),
        () -> catalog.restock(id, b.delta(), b.reason(), CurrentUser.id()), p -> id.toString(), ref -> catalog.adminGet(id));
  }

  /** Stock history, newest first. Leave productId out for every product. */
  @GetMapping("/stock-movements")
  public Page<CatalogService.StockMovementView> stockMovements(@RequestParam(required = false) UUID productId,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
    return catalog.stockMovements(productId, page, size);
  }

  /**
   * Uploads a product picture (multipart field {@code file}), stores a 400 px and a 1000 px version and links it to the product.
   * <p>A validation error is returned for an empty file, a file that is not an image, or one that is too large.
   * @param variants {@code one} (default) sets the picture on this pack only; {@code missing} also on the other packs of the
   *                 product that have none; {@code all} on every pack, replacing existing pictures
   */
  @PostMapping("/products/{id}/image")
  public AdminProduct image(@PathVariable UUID id, @RequestParam("file") MultipartFile file,
                            @RequestParam(value = "variants", required = false) String variants) throws IOException {
    CatalogService.ImageScope scope = CatalogService.ImageScope.parse(variants);
    if (file.isEmpty()) throw ApiException.validation("file", "file is empty");
    return catalog.setImage(id, images.save(file.getBytes(), "products"), scope);
  }

  /** Creates a category; answers 201 Created and is repeat-safe with an {@code Idempotency-Key}. */
  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  public Category createCategory(@Valid @RequestBody CategoryBody b, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    return AdminOps.run(ops, key, "category-create", CurrentUser.id(), AdminOps.fingerprint(b),
        () -> catalog.createCategory(b.nameEn(), b.nameGu(), b.sortOrder() == null ? 0 : b.sortOrder(), b.active() == null || b.active()),
        c -> c.id().toString(), ref -> catalog.categories().stream().filter(c -> c.id().toString().equals(ref)).findFirst().orElse(null));
  }

  /** Every category including hidden ones (with active and imageUrl), in the shop order. */
  @GetMapping("/categories")
  public List<AdminCategory> allCategories() { return catalog.adminCategories(); }

  /** Deletes a category; 409 {@code CATEGORY_IN_USE} while a product or coupon refers to it, 404 when unknown; answers 204. */
  @DeleteMapping("/categories/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCategory(@PathVariable UUID id) { catalog.deleteCategory(id); }

  /** Uploads a category picture (multipart field {@code file}) with the same rules as a product picture; returns the category. */
  @PostMapping("/categories/{id}/image")
  public AdminCategory categoryImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
    if (file.isEmpty()) throw ApiException.validation("file", "file is empty");
    return catalog.setCategoryImage(id, images.save(file.getBytes(), "categories"));
  }

  /** Renames, reorders or switches a category on or off; 404 when the category does not exist. */
  @PutMapping("/categories/{id}")
  public Category updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryBody b) {
    return catalog.updateCategory(id, b.nameEn(), b.nameGu(), b.sortOrder() == null ? 0 : b.sortOrder(), b.active() == null || b.active());
  }

  /** Lists all hot-sale banners, including inactive and expired ones. */
  @GetMapping("/hot-banners")
  public List<HotBanner> banners() { return catalog.adminBanners(); }

  /** Creates a hot-sale banner; answers 201 Created. */
  @PostMapping("/hot-banners")
  @ResponseStatus(HttpStatus.CREATED)
  public HotBanner createBanner(@Valid @RequestBody BannerBody b) {
    return catalog.createBanner(b.title(), b.subtitle(), b.imageUrl(), b.startAt(), b.endAt(), b.active() == null || b.active());
  }

  /** Updates a hot-sale banner; 404 when it does not exist. */
  @PutMapping("/hot-banners/{id}")
  public HotBanner updateBanner(@PathVariable UUID id, @Valid @RequestBody BannerBody b) {
    return catalog.updateBanner(id, b.title(), b.subtitle(), b.imageUrl(), b.startAt(), b.endAt(), b.active() == null || b.active());
  }

  /** Deletes a hot-sale banner; answers 204 No Content. */
  @DeleteMapping("/hot-banners/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteBanner(@PathVariable UUID id) { catalog.deleteBanner(id); }
}
