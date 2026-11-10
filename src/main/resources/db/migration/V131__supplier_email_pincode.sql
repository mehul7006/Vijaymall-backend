-- Optional contact details of a supplier: e-mail address (max 254) and a 6 digit pincode; empty means not given.
ALTER TABLE supplier
  ADD COLUMN email text NOT NULL DEFAULT '',
  ADD COLUMN pincode text NOT NULL DEFAULT '';
