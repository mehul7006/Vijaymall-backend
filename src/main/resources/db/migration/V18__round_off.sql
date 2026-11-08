-- Counter bills can be rounded to the nearest rupee (admin setting gst.roundOff). The difference is kept on the bill, so the invoice can
-- show taxable value + CGST + SGST +/- round-off = total, and returns still refund the exact value of the goods.
ALTER TABLE pos_sale ADD COLUMN round_off_paise bigint NOT NULL DEFAULT 0;
