-- Changes asked for by the Flutter app (BACKEND_INTEGRATION_GAPS.md). Money stays in paise.

-- 2.1 Mobile login: a customer may exist with only a verified mobile number, so email becomes optional.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ADD COLUMN mobile_verified boolean NOT NULL DEFAULT false;
ALTER TABLE users ADD CONSTRAINT users_contact_check CHECK (email IS NOT NULL OR mobile IS NOT NULL);
-- Only a number proven by an OTP is unique. A mobile typed into a profile is not trusted for login.
CREATE UNIQUE INDEX users_mobile_verified ON users (mobile) WHERE mobile_verified;

ALTER TABLE otp_challenge ALTER COLUMN email DROP NOT NULL;
ALTER TABLE otp_challenge ADD COLUMN mobile text;
ALTER TABLE otp_challenge ADD CONSTRAINT otp_challenge_target CHECK (email IS NOT NULL OR mobile IS NOT NULL);
CREATE INDEX otp_challenge_mobile ON otp_challenge (mobile, created_at DESC) WHERE mobile IS NOT NULL;

-- 2.2 Product variants: every pack is its own row; packs of one product share product_group_id.
ALTER TABLE product ADD COLUMN product_group_id uuid;
UPDATE product SET product_group_id = id;
ALTER TABLE product ALTER COLUMN product_group_id SET NOT NULL;
CREATE INDEX product_group ON product (product_group_id);

-- 2.11 Ratings and reviews. rating / review_count on product are a cache of product_review.
ALTER TABLE product ADD COLUMN rating double precision NOT NULL DEFAULT 0 CHECK (rating >= 0 AND rating <= 5);
ALTER TABLE product ADD COLUMN review_count int NOT NULL DEFAULT 0 CHECK (review_count >= 0);

CREATE TABLE product_review (
  id uuid PRIMARY KEY,
  product_id uuid NOT NULL REFERENCES product(id),
  user_id uuid NOT NULL REFERENCES users(id),
  rating int NOT NULL CHECK (rating BETWEEN 1 AND 5),
  comment text NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  UNIQUE (product_id, user_id)
);
CREATE INDEX product_review_product ON product_review (product_id, created_at DESC);

-- 2.3 Order lines describe themselves: Gujarati name and pack label are frozen at order time.
ALTER TABLE order_item ADD COLUMN name_gu_snapshot text NOT NULL DEFAULT '';
ALTER TABLE order_item ADD COLUMN unit_label_snapshot text NOT NULL DEFAULT '';
-- Best effort for orders placed before this migration: use what the product says today.
UPDATE order_item oi
   SET name_gu_snapshot = p.name_gu,
       unit_label_snapshot = CASE WHEN p.pack_size <> '' THEN p.pack_size ELSE p.unit END
  FROM product p
 WHERE p.id = oi.product_id;

-- 2.4 cancel reason and 2.8 delivery slot
ALTER TABLE orders ADD COLUMN cancel_reason text;
ALTER TABLE orders ADD COLUMN delivery_slot text;

-- 2.5 coupon flags
ALTER TABLE coupon ADD COLUMN works_with_coins boolean NOT NULL DEFAULT true;
ALTER TABLE coupon ADD COLUMN first_order_only boolean NOT NULL DEFAULT false;

-- 2.10 wishlist
CREATE TABLE wishlist_item (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES users(id),
  product_id uuid NOT NULL REFERENCES product(id),
  created_at timestamptz NOT NULL,
  UNIQUE (user_id, product_id)
);
CREATE INDEX wishlist_user ON wishlist_item (user_id, created_at DESC);

-- 2.6 and 2.7: the stock history and the period reports read by time range
CREATE INDEX stock_movement_created ON stock_movement (created_at DESC);
CREATE INDEX orders_placed ON orders (placed_at);
