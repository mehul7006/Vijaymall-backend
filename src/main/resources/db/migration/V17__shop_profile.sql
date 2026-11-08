-- What the apps show about the shop beyond its name, address and phone (those stay in shop_setting): logo, contact links, opening hours,
-- a holiday note, and a switch to pause online ordering. One row, edited by the admin.
CREATE TABLE shop_profile (
  id int PRIMARY KEY CHECK (id = 1),
  logo_url text NOT NULL DEFAULT '',
  email text NOT NULL DEFAULT '',
  whatsapp text NOT NULL DEFAULT '',
  map_url text NOT NULL DEFAULT '',
  website text NOT NULL DEFAULT '',
  instagram text NOT NULL DEFAULT '',
  facebook text NOT NULL DEFAULT '',
  hours text NOT NULL,
  holiday_note text NOT NULL DEFAULT '',
  online_orders_enabled boolean NOT NULL DEFAULT true,
  online_orders_message text NOT NULL DEFAULT '',
  updated_at timestamptz NOT NULL
);
INSERT INTO shop_profile (id, hours, updated_at) VALUES (1,
  '[{"day":"MON","open":"09:00","close":"21:00","closed":false},{"day":"TUE","open":"09:00","close":"21:00","closed":false},{"day":"WED","open":"09:00","close":"21:00","closed":false},{"day":"THU","open":"09:00","close":"21:00","closed":false},{"day":"FRI","open":"09:00","close":"21:00","closed":false},{"day":"SAT","open":"09:00","close":"21:00","closed":false},{"day":"SUN","open":"09:00","close":"21:00","closed":false}]',
  now());
