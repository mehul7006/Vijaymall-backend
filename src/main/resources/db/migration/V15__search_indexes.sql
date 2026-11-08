-- Searching by part of a name, e-mail, mobile, order number or product name ("contains") cannot use a normal index and reads
-- every row. Trigram indexes make those searches fast at tens of thousands of customers and hundreds of thousands of orders.
-- If the database does not allow the pg_trgm extension (some managed services need it switched on by the provider), the
-- migration still succeeds and searches simply stay as they were.
DO $$
BEGIN
  CREATE EXTENSION IF NOT EXISTS pg_trgm;
EXCEPTION WHEN OTHERS THEN
  RAISE NOTICE 'pg_trgm is not available (%), search indexes skipped', SQLERRM;
END $$;

DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
    CREATE INDEX IF NOT EXISTS orders_order_no_trgm ON orders USING gin (lower(order_no) gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS users_name_trgm ON users USING gin (lower(coalesce(name, '')) gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS users_email_trgm ON users USING gin (lower(coalesce(email, '')) gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS users_mobile_trgm ON users USING gin (coalesce(mobile, '') gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS product_name_en_trgm ON product USING gin (lower(name_en) gin_trgm_ops);
    CREATE INDEX IF NOT EXISTS product_name_gu_trgm ON product USING gin (lower(name_gu) gin_trgm_ops);
  END IF;
END $$;
