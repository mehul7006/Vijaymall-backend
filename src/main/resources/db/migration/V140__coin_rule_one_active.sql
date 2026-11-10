-- Only one earning rule may be active at a time. Existing data is repaired first: of several active rules only the
-- most recently updated one stays on (rows start with the same updated_at, so the id breaks the tie).
ALTER TABLE coin_rule ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

UPDATE coin_rule SET active = false
 WHERE active
   AND id <> (SELECT id FROM coin_rule WHERE active ORDER BY updated_at DESC, id DESC LIMIT 1);

CREATE UNIQUE INDEX coin_rule_one_active ON coin_rule (active) WHERE active;
