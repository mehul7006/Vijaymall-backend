package com.vijay.mall.promotion.api;

import com.vijay.mall.common.security.CurrentUser;
import com.vijay.mall.promotion.app.PromotionService;
import com.vijay.mall.promotion.domain.Coupon;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@RequestMapping("/api/v1/admin/coupons")
public class AdminCouponController {
  public record Body(@NotBlank String code, @NotBlank String kind, @Min(1) long value, Long minOrderPaise,
                     Long maxDiscountPaise, UUID categoryId, Integer perUserLimit, Integer totalLimit,
                     Instant startAt, Instant endAt, Boolean active) {
    Coupon toCoupon() {
      return new Coupon(null, code, kind, value, minOrderPaise == null ? 0 : minOrderPaise,
          maxDiscountPaise == null ? 0 : maxDiscountPaise, categoryId, perUserLimit == null ? 0 : perUserLimit,
          totalLimit == null ? 0 : totalLimit, 0, startAt, endAt, active == null || active);
    }
  }

  private final PromotionService promotions;

  public AdminCouponController(PromotionService promotions) { this.promotions = promotions; }

  @GetMapping
  public List<Coupon> list() { return promotions.list(); }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Coupon create(@Valid @RequestBody Body b) { return promotions.create(b.toCoupon(), CurrentUser.id()); }

  @PutMapping("/{id}")
  public Coupon update(@PathVariable UUID id, @Valid @RequestBody Body b) {
    return promotions.update(id, b.toCoupon(), CurrentUser.id());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable UUID id) { promotions.delete(id, CurrentUser.id()); }
}
