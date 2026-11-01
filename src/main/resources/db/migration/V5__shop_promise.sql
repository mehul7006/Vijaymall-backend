-- A short line shown to customers about the shop (for example its delivery promise). Public, edited by the admin.
ALTER TABLE shop_setting ADD COLUMN promise text NOT NULL DEFAULT '';
