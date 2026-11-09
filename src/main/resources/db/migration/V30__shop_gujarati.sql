-- Gujarati versions of the customer-facing shop texts (empty = the apps fall back to English) and a short name for tight spaces.
ALTER TABLE shop_setting ADD COLUMN short_name text NOT NULL DEFAULT '';
ALTER TABLE shop_setting ADD COLUMN shop_name_gu text NOT NULL DEFAULT '';
ALTER TABLE shop_setting ADD COLUMN address_gu text NOT NULL DEFAULT '';
ALTER TABLE shop_setting ADD COLUMN promise_gu text NOT NULL DEFAULT '';
ALTER TABLE shop_setting ADD COLUMN details_gu text NOT NULL DEFAULT '';
ALTER TABLE shop_profile ADD COLUMN holiday_note_gu text NOT NULL DEFAULT '';
