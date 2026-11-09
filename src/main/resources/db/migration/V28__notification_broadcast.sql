-- Admin announcements: saved customer segments, broadcasts (queued, sent in batches by a background worker) and the inbox link.

CREATE TABLE notification_segment (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  description text NOT NULL DEFAULT '',
  match_mode text NOT NULL CHECK (match_mode IN ('ALL','ANY')),
  rules jsonb NOT NULL,
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  archived boolean NOT NULL DEFAULT false
);
CREATE UNIQUE INDEX notification_segment_name ON notification_segment (lower(name)) WHERE NOT archived;
CREATE INDEX notification_segment_recent ON notification_segment (created_at DESC) WHERE NOT archived;

CREATE TABLE notification_broadcast (
  id uuid PRIMARY KEY,
  audience text NOT NULL CHECK (audience IN ('ALL','CUSTOMERS','STAFF','ADMINS','CASHIERS','USERS','SEGMENT')),
  title text NOT NULL,
  body text NOT NULL,
  ref text,
  status text NOT NULL CHECK (status IN ('QUEUED','SENDING','DONE','FAILED')),
  recipients bigint NOT NULL DEFAULT 0,
  delivered bigint NOT NULL DEFAULT 0,
  pushed bigint NOT NULL DEFAULT 0,
  attempts int NOT NULL DEFAULT 0,
  last_user_id uuid,
  recipient_ids jsonb,
  segment_id uuid,
  segment_rules jsonb,
  skipped jsonb NOT NULL DEFAULT '[]'::jsonb,
  created_by uuid NOT NULL REFERENCES users(id),
  created_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  started_at timestamptz,
  finished_at timestamptz
);
CREATE INDEX notification_broadcast_open ON notification_broadcast (created_at) WHERE status IN ('QUEUED','SENDING');
CREATE INDEX notification_broadcast_recent ON notification_broadcast (created_at DESC);

ALTER TABLE notification ADD COLUMN broadcast_id uuid REFERENCES notification_broadcast(id);
CREATE UNIQUE INDEX notification_broadcast_user ON notification (broadcast_id, user_id) WHERE broadcast_id IS NOT NULL;
