-- GST rates become decimal: the CBIC master has 0.25 and 1.5 besides the whole-number rates, and 40 was added on 22 Sep 2025.
-- numeric(6,3) holds every rate exactly. Whole-number rates convert without any change of value. The accepted set
-- (0, 0.25, 1.5, 3, 5, 12, 18, 28, 40) is enforced by the application (GstRates); the database only checks the range.
-- The int -> numeric change rewrites the three tables: run migrations with the app stopped (see deploy/README.md).
-- purchase.freight_gst_percent stays int (freight is taxed at a whole-number rate).
DO $$
DECLARE c text;
BEGIN
  FOR c IN SELECT conname FROM pg_constraint
           WHERE conrelid = 'product_pos'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%gst_percent%'
  LOOP
    EXECUTE format('ALTER TABLE product_pos DROP CONSTRAINT %I', c);
  END LOOP;
END $$;
ALTER TABLE product_pos ALTER COLUMN gst_percent TYPE numeric(6,3) USING gst_percent;
ALTER TABLE product_pos ADD CONSTRAINT product_pos_gst_percent_range CHECK (gst_percent >= 0 AND gst_percent <= 100);
ALTER TABLE pos_sale_item ALTER COLUMN gst_percent TYPE numeric(6,3) USING gst_percent;
ALTER TABLE purchase_item ALTER COLUMN gst_percent TYPE numeric(6,3) USING gst_percent;
