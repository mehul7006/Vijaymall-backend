-- Finds an earlier delivery with the same supplier and invoice number (trimmed, case-insensitive) without scanning the table; used for the
-- duplicate-invoice warning when a delivery is recorded. Deliveries without an invoice number are not indexed.
CREATE INDEX purchase_supplier_invoice ON purchase (lower(btrim(supplier_name)), lower(btrim(invoice_no)), created_at)
  WHERE invoice_no <> '';
