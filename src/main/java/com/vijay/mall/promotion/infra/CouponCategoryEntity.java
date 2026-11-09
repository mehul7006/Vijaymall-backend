package com.vijay.mall.promotion.infra;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/** One category a coupon is limited to. A coupon with no rows here covers every category. */
@Entity
@Table(name = "coupon_category")
@IdClass(CouponCategoryId.class)
class CouponCategoryEntity {
  @Id UUID couponId;
  @Id UUID categoryId;

  /** Required by JPA; not for application code. */
  protected CouponCategoryEntity() {}
}
