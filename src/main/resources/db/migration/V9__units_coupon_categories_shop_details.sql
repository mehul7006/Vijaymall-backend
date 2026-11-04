-- Feedback round: per-category coupons, admin-managed units, shop details from settings.

-- 1. A coupon can be limited to several categories. No rows for a coupon means "every category".
--    coupon.category_id stays (the first category) so older readers keep working.
CREATE TABLE coupon_category (
  coupon_id   uuid NOT NULL REFERENCES coupon(id) ON DELETE CASCADE,
  category_id uuid NOT NULL REFERENCES category(id),
  PRIMARY KEY (coupon_id, category_id)
);
CREATE INDEX coupon_category_category ON coupon_category (category_id);
INSERT INTO coupon_category (coupon_id, category_id)
SELECT id, category_id FROM coupon WHERE category_id IS NOT NULL;

-- 2. The share of the coupon discount that landed on each order line (only lines of the coupon's categories get one),
--    so an order can show the discount per line and per category. Older orders keep 0.
ALTER TABLE order_item ADD COLUMN coupon_discount_paise bigint NOT NULL DEFAULT 0;

-- 3. Units of measure, managed by the admin. product.unit holds the unit code.
--    sold_by tells the counter how the unit is weighed: UNIT counts pieces, WEIGHT is priced per 1000 base units
--    (grams for kg, millilitres for litres).
CREATE TABLE unit_of_measure (
  code       text PRIMARY KEY,
  name_en    text NOT NULL,
  name_gu    text NOT NULL DEFAULT '',
  sold_by    text NOT NULL DEFAULT 'UNIT' CHECK (sold_by IN ('UNIT','WEIGHT')),
  sort_order int  NOT NULL DEFAULT 0,
  active     boolean NOT NULL DEFAULT true
);
CREATE UNIQUE INDEX unit_of_measure_code_ci ON unit_of_measure (lower(code));

INSERT INTO unit_of_measure (code, name_en, name_gu, sold_by, sort_order) VALUES
  ('pc',     'Piece',  'નંગ',     'UNIT',   1),
  ('dozen',  'Dozen',  'ડઝન',    'UNIT',   2),
  ('pack',   'Pack',   'પેક',     'UNIT',   3),
  ('pouch',  'Pouch',  'પાઉચ',   'UNIT',   4),
  ('bottle', 'Bottle', 'બોટલ',   'UNIT',   5),
  ('tube',   'Tube',   'ટ્યુબ',    'UNIT',   6),
  ('g',      'Gram',   'ગ્રામ',    'UNIT',   7),
  ('kg',     'Kilogram', 'કિલો',  'WEIGHT', 8),
  ('ml',     'Millilitre', 'મિલી', 'UNIT',  9),
  ('L',      'Litre',  'લિટર',   'WEIGHT', 10);

-- Any unit a product already uses that is not in the list above becomes a unit too, so nothing is orphaned.
INSERT INTO unit_of_measure (code, name_en, sort_order)
SELECT DISTINCT ON (lower(p.unit)) p.unit, p.unit, 100
  FROM product p
 WHERE p.unit <> ''
   AND NOT EXISTS (SELECT 1 FROM unit_of_measure u WHERE lower(u.code) = lower(p.unit));

-- Make products use the unit's own spelling of its code (for example 'l' becomes 'L').
UPDATE product p SET unit = u.code
  FROM unit_of_measure u
 WHERE lower(u.code) = lower(p.unit) AND p.unit <> u.code;

-- 4. A longer "about the shop" text for customers, edited with the other shop settings.
ALTER TABLE shop_setting ADD COLUMN details text NOT NULL DEFAULT '';
