-- The audit trail only grows and is read newest first, filtered by person, action or record. Without these every page
-- of the audit screen scanned the whole table (about 190 ms at one million rows, and growing).
CREATE INDEX audit_log_recent ON audit_log (created_at DESC, id);
CREATE INDEX audit_log_by_actor ON audit_log (actor_id, created_at DESC, id) WHERE actor_id IS NOT NULL;
CREATE INDEX audit_log_by_action ON audit_log (action, created_at DESC, id);
CREATE INDEX audit_log_by_record ON audit_log (entity, entity_id, created_at DESC, id);
