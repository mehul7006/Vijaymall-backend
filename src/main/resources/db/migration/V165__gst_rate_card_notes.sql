-- Billing now stores 0.25, 1.5 and 40 (common.tax.GstRates), so the rate-card notes that said "Not accepted by the server" are cleared.
UPDATE gst_rate_card SET note = '' WHERE code IN ('GST_0_25', 'GST_1_5', 'GST_40') AND note LIKE 'Not accepted by the server%';
