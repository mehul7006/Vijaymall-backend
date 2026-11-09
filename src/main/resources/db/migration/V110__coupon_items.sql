-- Item-specific coupons. A coupon may list products (variants) and/or product groups (every variant of one product) besides
-- categories. A cart line is covered when its category, its product or its product group is listed; a coupon with none of the three
-- lists covers every line. No rows for a coupon means "no item limit".
CREATE TABLE coupon_product (
  coupon_id  uuid NOT NULL REFERENCES coupon(id) ON DELETE CASCADE,
  product_id uuid NOT NULL REFERENCES product(id),
  PRIMARY KEY (coupon_id, product_id)
);
CREATE INDEX coupon_product_product ON coupon_product (product_id);

CREATE TABLE coupon_product_group (
  coupon_id        uuid NOT NULL REFERENCES coupon(id) ON DELETE CASCADE,
  product_group_id uuid NOT NULL,
  PRIMARY KEY (coupon_id, product_group_id)
);
CREATE INDEX coupon_product_group_group ON coupon_product_group (product_group_id);
