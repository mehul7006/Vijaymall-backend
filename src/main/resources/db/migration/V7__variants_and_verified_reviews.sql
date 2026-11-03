-- Variants: one product (a group of rows sharing product_group_id) with several sellable variants.
-- Each variant is still a product row, so stock, price, barcodes, cart and order lines keep pointing at it.
-- variant_name is the flavour / colour / size name ("Mango"); pack_size stays the quantity label ("500 g").
ALTER TABLE product ADD COLUMN variant_name text NOT NULL DEFAULT '';
ALTER TABLE product ADD COLUMN variant_order int NOT NULL DEFAULT 0;

-- A review written for a delivered product remembers the order that delivered it (null for older reviews).
ALTER TABLE product_review ADD COLUMN order_id uuid REFERENCES orders(id);
CREATE INDEX product_review_order ON product_review (order_id);
