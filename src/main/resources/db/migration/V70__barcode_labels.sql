-- Store-internal barcodes, label templates and the print history.

-- One counter per in-store prefix; the next generated EAN-13 takes the next number (updated atomically, so two requests never share one).
CREATE TABLE barcode_sequence (
    prefix     text PRIMARY KEY,
    next_value bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- A saved label layout: size, sheet grid, barcode type and which fields are printed (fields is JSON text).
CREATE TABLE label_template (
    id             uuid PRIMARY KEY,
    name           text NOT NULL,
    kind           text NOT NULL CHECK (kind IN ('THERMAL', 'SHEET')),
    width_mm       double precision NOT NULL,
    height_mm      double precision NOT NULL,
    sheet_columns  int NOT NULL DEFAULT 1,
    sheet_rows     int NOT NULL DEFAULT 1,
    gap_x_mm       double precision NOT NULL DEFAULT 0,
    gap_y_mm       double precision NOT NULL DEFAULT 0,
    margin_left_mm double precision NOT NULL DEFAULT 0,
    margin_top_mm  double precision NOT NULL DEFAULT 0,
    barcode_type   text NOT NULL CHECK (barcode_type IN ('EAN13', 'CODE128', 'QR')),
    fields         text NOT NULL,
    built_in       boolean NOT NULL DEFAULT false,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX label_template_name ON label_template (lower(name));

-- Who printed labels for which variant, with which template and in which mode (one row per variant per print).
CREATE TABLE label_print_history (
    id            uuid PRIMARY KEY,
    printed_at    timestamptz NOT NULL,
    actor_id      uuid,
    template_id   uuid,
    template_name text NOT NULL,
    variant_id    uuid NOT NULL,
    variant_name  text NOT NULL,
    copies        int NOT NULL,
    mode          text NOT NULL
);
CREATE INDEX label_print_history_time ON label_print_history (printed_at DESC, id);
CREATE INDEX label_print_history_variant ON label_print_history (variant_id, printed_at DESC);

INSERT INTO label_template (id, name, kind, width_mm, height_mm, sheet_columns, sheet_rows, gap_x_mm, gap_y_mm, margin_left_mm, margin_top_mm, barcode_type, fields, built_in) VALUES
 ('00000000-0000-0000-0000-00000000a001', '50 x 25 mm thermal', 'THERMAL', 50, 25, 1, 1, 0, 0, 0, 0, 'EAN13',
  '{"shopName":false,"productName":"EN","packSize":true,"mrp":true,"price":true,"packedOn":false,"bestBefore":false,"batchNo":false,"pricePerUnit":false,"fontSize":7,"alignment":"CENTER"}', true),
 ('00000000-0000-0000-0000-00000000a002', '38 x 25 mm thermal', 'THERMAL', 38, 25, 1, 1, 0, 0, 0, 0, 'EAN13',
  '{"shopName":false,"productName":"EN","packSize":true,"mrp":false,"price":true,"packedOn":false,"bestBefore":false,"batchNo":false,"pricePerUnit":false,"fontSize":6,"alignment":"CENTER"}', true),
 ('00000000-0000-0000-0000-00000000a003', 'A4 sheet 3 x 8', 'SHEET', 64, 34, 3, 8, 2, 2, 7, 5.5, 'EAN13',
  '{"shopName":true,"productName":"EN","packSize":true,"mrp":true,"price":true,"packedOn":false,"bestBefore":false,"batchNo":false,"pricePerUnit":false,"fontSize":8,"alignment":"CENTER"}', true);
