-- A purchase line's invoice unit cost may be fractional paise (a weighed product bought at Rs 55/kg costs 5.5 paise per gram).
-- Whole-number rows keep their value exactly; the existing CHECK (unit_cost_paise >= 0) stays.
ALTER TABLE purchase_item ALTER COLUMN unit_cost_paise TYPE numeric(18,4);
