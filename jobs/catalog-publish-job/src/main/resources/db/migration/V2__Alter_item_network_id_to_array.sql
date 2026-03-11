-- Migration to change network_id column from TEXT to TEXT[] in item table (if still TEXT)
ALTER TABLE item DROP COLUMN IF EXISTS network_id;
ALTER TABLE item ADD COLUMN IF NOT EXISTS network_id TEXT[];
