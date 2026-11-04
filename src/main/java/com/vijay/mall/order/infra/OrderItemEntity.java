package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "order_item")
class OrderItemEntity extends BaseEntity {
  UUID orderId;
  UUID productId;
  @Column(name = "name_snapshot") String name;
  @Column(name = "name_gu_snapshot") String nameGu;
  @Column(name = "unit_label_snapshot") String unitLabel;
  long unitPricePaise;
  int qty;
  long lineTotalPaise;
  /** This line's share of the order's coupon discount (0 when the coupon did not cover its category). */
  long couponDiscountPaise;
  @Column(name = "earns_coins_snapshot") boolean earnsCoins;
  @Column(name = "coin_redeemable_snapshot") boolean coinRedeemable;

  protected OrderItemEntity() {}
}
