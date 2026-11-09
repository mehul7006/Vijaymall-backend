package com.vijay.mall.order.infra;

import com.vijay.mall.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Row of {@code order_item}: one line of an order with name, price and flags copied at order time, plus how many units were returned.
 */
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
  int returnedQty;
  @Column(name = "earns_coins_snapshot") boolean earnsCoins;
  @Column(name = "coin_redeemable_snapshot") boolean coinRedeemable;

  /** For JPA. */
  protected OrderItemEntity() {}
}
