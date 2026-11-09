package com.vijay.mall.promotion.api;

import io.swagger.v3.oas.annotations.media.Schema;
import com.vijay.mall.common.Limits;
import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.promotion.app.PromotionService;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.vijay.mall.promotion.domain.AppliesTo;
import com.vijay.mall.promotion.domain.Coupon;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin screen for coupons under {@code /api/v1/admin/coupons}. Admin only (see SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/coupons")
public class AdminCouponController {
  /**
   * Request body for creating or changing a coupon. Money is in paise.
   * @param code the code customers type; stored upper-case; at most 30 characters, letters, digits, - and _ only (no spaces)
   * @param kind {@code FLAT} (value is paise off) or {@code PERCENT} (value is a percent, at most 100)
   * @param value the discount size, at least 1
   * @param minOrderPaise minimum cart value; null means none
   * @param maxDiscountPaise cap on a percent discount; null means no cap
   * @param categoryId single category limit kept for older clients
   * @param perUserLimit uses per customer; null or 0 means unlimited
   * @param totalLimit total uses; null or 0 means unlimited
   * @param startAt first moment it works; null means no limit
   * @param endAt last moment it works; null means no limit
   * @param active whether it is switched on; null means true
   * @param worksWithCoins whether coins may be spent in the same order; null means true
   * @param firstOrderOnly true to allow it only for a customer with no earlier order
   * @param categoryIds categories it covers; empty or null means every category
   * @param productIds products (variants) it covers
   * @param productGroupIds products it covers, with all their variants. With categoryIds, productIds and productGroupIds all empty the
   *     coupon covers every item; otherwise a cart line is covered when it matches any of the three lists. At most 200 products
   *     and product groups together; an unknown id is a 400 naming the field and position, for example {@code productIds[2]}
   */
  @Schema(name = "CouponBody")
  public record Body(@NotBlank @Size(max = Limits.COUPON_CODE) String code, @NotBlank String kind,
                     @NotNull @Min(1) @Max(Limits.MAX_PRICE_PAISE) Long value,
                     @Min(0) @Max(Limits.MAX_PRICE_PAISE) Long minOrderPaise, @Min(0) @Max(Limits.MAX_PRICE_PAISE) Long maxDiscountPaise, UUID categoryId, Integer perUserLimit, Integer totalLimit,
                     Instant startAt, Instant endAt, Boolean active, Boolean worksWithCoins, Boolean firstOrderOnly,
                     List<UUID> categoryIds, List<UUID> productIds, List<UUID> productGroupIds) {
    /** Without product or product group limits. */
    public Body(String code, String kind, Long value, Long minOrderPaise, Long maxDiscountPaise, UUID categoryId,
                Integer perUserLimit, Integer totalLimit, Instant startAt, Instant endAt, Boolean active,
                Boolean worksWithCoins, Boolean firstOrderOnly, List<UUID> categoryIds) {
      this(code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, startAt, endAt,
          active, worksWithCoins, firstOrderOnly, categoryIds, null, null);
    }

    /** Without categoryIds: the single categoryId (or every category when that is null too), as before. */
    public Body(String code, String kind, long value, Long minOrderPaise, Long maxDiscountPaise, UUID categoryId,
                Integer perUserLimit, Integer totalLimit, Instant startAt, Instant endAt, Boolean active,
                Boolean worksWithCoins, Boolean firstOrderOnly) {
      this(code, kind, value, minOrderPaise, maxDiscountPaise, categoryId, perUserLimit, totalLimit, startAt, endAt,
          active, worksWithCoins, firstOrderOnly, null, null, null);
    }

    /** categoryIds limits the coupon to those categories (several allowed); empty or missing means every category. */
    Coupon toCoupon() {
      return new Coupon(null, code, kind, value, minOrderPaise == null ? 0 : minOrderPaise,
          maxDiscountPaise == null ? 0 : maxDiscountPaise, categoryId, perUserLimit == null ? 0 : perUserLimit,
          totalLimit == null ? 0 : totalLimit, 0, startAt, endAt, active == null || active,
          worksWithCoins == null || worksWithCoins, Boolean.TRUE.equals(firstOrderOnly), categoryIds, productIds, productGroupIds);
    }
  }

  /**
   * A coupon as the admin screen shows it: every field of the coupon, {@code appliesTo} (type ALL, CATEGORIES, ITEMS or MIXED with
   * the named categories, products and product groups, and whether each is switched on) and {@code warnings} (for example a
   * listed product that is switched off).
   */
  @Schema(name = "CouponView")
  public record View(@JsonUnwrapped Coupon coupon, AppliesTo appliesTo, List<String> warnings) {
    static View of(PromotionService.AdminView v) { return new View(v.coupon(), v.appliesTo(), v.warnings()); }
  }

  private final PromotionService promotions;

  /** Creates the controller (wired by Spring). */
  public AdminCouponController(PromotionService promotions) { this.promotions = promotions; }

  /** Lists every coupon, including inactive ones. */
  @GetMapping
  public List<View> list() { return promotions.listWithNames().stream().map(View::of).toList(); }

  /** One coupon with the names of what it is for. 404 for an unknown id. */
  @GetMapping("/{id}")
  public View get(@PathVariable UUID id) { return View.of(promotions.detail(id)); }

  /** Creates a coupon (201). Fails with 422 for invalid values or a code that already exists. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public View create(@Valid @RequestBody Body b) {
    Coupon c = promotions.create(b.toCoupon(), CurrentUser.id());
    return View.of(promotions.detail(c.id()));
  }

  /** Changes a coupon. Fails with 404 for an unknown id and 422 for invalid values or a duplicate code. */
  @PutMapping("/{id}")
  public View update(@PathVariable UUID id, @Valid @RequestBody Body b) {
    promotions.update(id, b.toCoupon(), CurrentUser.id());
    return View.of(promotions.detail(id));
  }

  /** Deletes a coupon, or only switches it off when it has already been used (204). */
  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { promotions.delete(id, CurrentUser.id()); }
}
