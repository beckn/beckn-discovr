-- Remove identity/audit columns from item and provider_offer tables.
-- Discovr has no ownership enforcement; identity is an internal Catalg concern.

DROP INDEX IF EXISTS idx_item_subscriber;

ALTER TABLE item DROP COLUMN IF EXISTS created_by;
ALTER TABLE item DROP COLUMN IF EXISTS updated_by;
ALTER TABLE item DROP COLUMN IF EXISTS subscriber_id;

ALTER TABLE provider_offer DROP COLUMN IF EXISTS created_by;
ALTER TABLE provider_offer DROP COLUMN IF EXISTS updated_by;
ALTER TABLE provider_offer DROP COLUMN IF EXISTS subscriber_id;
