-- Settings the admin can change from the app without a restart or a redeploy (payment keys, push, SMS, e-mail, log levels, counter
-- rules...). Secret values are stored encrypted (AES-GCM, key CONFIG_ENCRYPTION_KEY from the environment), never in plain text.
CREATE TABLE app_setting (
  name text PRIMARY KEY,
  value text NOT NULL,
  secret boolean NOT NULL DEFAULT false,
  updated_at timestamptz NOT NULL,
  updated_by uuid REFERENCES users(id)
);
