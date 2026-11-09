-- Every Paytm machine has its own CARD_TERMINAL money account (provider paytm, identifier = terminal id), so it shows as a till channel.
-- payment_terminal.money_account_id is that account. A value saved before this release pointed at the bank the machine settles into: it
-- moves to the new account's settles_to_account_id. Safe to run again: it only touches machines without an account of their own.
DO $$
DECLARE
  t record;
  acc uuid;
  nm text;
  settles uuid;
BEGIN
  FOR t IN SELECT * FROM payment_terminal pt
           WHERE NOT EXISTS (SELECT 1 FROM money_account m WHERE m.id = pt.money_account_id AND m.kind = 'CARD_TERMINAL') ORDER BY created_at, id LOOP
    settles := NULL;
    IF t.money_account_id IS NOT NULL THEN
      SELECT id INTO settles FROM money_account WHERE id = t.money_account_id AND kind = 'BANK';
    END IF;
    SELECT id INTO acc FROM money_account WHERE kind = 'CARD_TERMINAL' AND provider = t.provider AND identifier = t.terminal_id LIMIT 1;
    IF acc IS NULL THEN
      nm := t.name;
      IF EXISTS (SELECT 1 FROM money_account m WHERE lower(m.name) = lower(nm)) THEN nm := t.name || ' (' || t.terminal_id || ')'; END IF;
      acc := gen_random_uuid();
      INSERT INTO money_account (id, name, kind, provider, identifier, settles_to_account_id, active, note, created_at, updated_at)
      VALUES (acc, nm, 'CARD_TERMINAL', t.provider, t.terminal_id, settles, t.active, 'Card machine', now(), now());
    END IF;
    UPDATE payment_terminal SET money_account_id = acc WHERE id = t.id;
  END LOOP;
END $$;
