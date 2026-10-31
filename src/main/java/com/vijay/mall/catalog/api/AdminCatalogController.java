package com.vijay.mall.catalog.api;

import com.vijay.mall.catalog.app.CatalogService;
import com.vijay.mall.catalog.app.ImageStore;
import com.vijay.mall.catalog.domain.AdminProduct;
import com.vijay.mall.catalog.domain.Category;
import com.vijay.mall.catalog.domain.HotBanner;
import com.vijay.mall.common.Page;
import com.vijay.mall.common.error.ApiException;
import com.vijay.mall.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {
  public record ProductBody(@NotNull UUID categoryId, @NotBlank String nameEn, @NotBlank String nameGu,
                            String description, @Min(0) long mrpPaise, @Min(0) long pricePaise, String unit,
                            String packSize, Boolean active, Boolean hotSale, Instant hotStart, Instant hotEnd,
                            Boolean earnsCoins, Boolean coinRedeemable, Integer qtyOnHand, Integer lowThreshold) {
    CatalogService.ProductInput toInput() {
      return new CatalogService.ProductInput(categoryId, nameEn, nameGu, description, mrpPaise, pricePaise, unit,
          packSize, active == null || active, Boolean.TRUE.equals(hotSale), hotStart, hotEnd,
          earnsCoins == null || earnsCoins, coinRedeemable == null || coinRedeemable,
          qtyOnHand == null ? 0 : qtyOnHand, lowThreshold == null ? 5 : lowThreshold);
    }
  }
  public record StockBody(int delta, String reason) {}
  public record CategoryBody(@NotBlank String nameEn, @NotBlank String nameGu, Integer sortOrder, Boolean active) {}
  public record BannerBody(@NotBlank String title, String subtitle, String imageUrl, Instant startAt, Instant endAt,
                           Boolean active) {}

  private final CatalogService catalog;
  private final ImageStore images;

  public AdminCatalogController(CatalogService catalog, ImageStore images) {
    this.catalog = catalog;
    this.images = images;
  }

  @GetMapping("/products")
  public Page<AdminProduct> list(@RequestParam(required = false) String q,
                                 @RequestParam(required = false) UUID categoryId,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
    return catalog.adminList(q, categoryId, page, size);
  }

  @PostMapping("/products")
  @ResponseStatus(HttpStatus.CREATED)
  public AdminProduct create(@Valid @RequestBody ProductBody b) { return catalog.create(b.toInput(), CurrentUser.id()); }

  @GetMapping("/products/{id}")
  public AdminProduct get(@PathVariable UUID id) { return catalog.adminGet(id); }

  @PutMapping("/products/{id}")
  public AdminProduct update(@PathVariable UUID id, @Valid @RequestBody ProductBody b) {
    return catalog.update(id, b.toInput(), CurrentUser.id());
  }

  @PostMapping("/products/{id}/stock")
  public AdminProduct stock(@PathVariable UUID id, @RequestBody StockBody b) {
    return catalog.restock(id, b.delta(), b.reason(), CurrentUser.id());
  }

  @PostMapping("/products/{id}/image")
  public AdminProduct image(@PathVariable UUID id, @RequestParam("file") MultipartFile file) throws IOException {
    if (file.isEmpty()) throw ApiException.validation("file", "file is empty");
    return catalog.setImage(id, images.save(file.getBytes(), "products"));
  }

  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  public Category createCategory(@Valid @RequestBody CategoryBody b) {
    return catalog.createCategory(b.nameEn(), b.nameGu(), b.sortOrder() == null ? 0 : b.sortOrder(), b.active() == null || b.active());
  }

  @PutMapping("/categories/{id}")
  public Category updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryBody b) {
    return catalog.updateCategory(id, b.nameEn(), b.nameGu(), b.sortOrder() == null ? 0 : b.sortOrder(), b.active() == null || b.active());
  }

  @GetMapping("/hot-banners")
  public List<HotBanner> banners() { return catalog.adminBanners(); }

  @PostMapping("/hot-banners")
  @ResponseStatus(HttpStatus.CREATED)
  public HotBanner createBanner(@Valid @RequestBody BannerBody b) {
    return catalog.createBanner(b.title(), b.subtitle(), b.imageUrl(), b.startAt(), b.endAt(), b.active() == null || b.active());
  }

  @PutMapping("/hot-banners/{id}")
  public HotBanner updateBanner(@PathVariable UUID id, @Valid @RequestBody BannerBody b) {
    return catalog.updateBanner(id, b.title(), b.subtitle(), b.imageUrl(), b.startAt(), b.endAt(), b.active() == null || b.active());
  }

  @DeleteMapping("/hot-banners/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteBanner(@PathVariable UUID id) { catalog.deleteBanner(id); }
}
