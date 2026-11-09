-- Square app icons (512 px and 192 px PNG) made from the shop logo; null when there is no logo or it was uploaded before icons existed.
ALTER TABLE shop_profile ADD COLUMN icon_url text;
ALTER TABLE shop_profile ADD COLUMN icon192_url text;
