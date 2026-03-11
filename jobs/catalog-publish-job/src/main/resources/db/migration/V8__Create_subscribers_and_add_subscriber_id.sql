-- Subscribers table: registered BAPs and NFOs (required by the Publish API).
-- subscriber_uri is unique so upserts by URI are idempotent.
CREATE TABLE IF NOT EXISTS subscribers (
    subscriber_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscriber_type VARCHAR(10)  NOT NULL CHECK (subscriber_type IN ('NFO', 'BAP')),
    subscriber_uri  VARCHAR(255) UNIQUE NOT NULL,
    display_name    VARCHAR(255),
    public_keys     JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Add subscriber_id FK to subscriptions only if the table exists.
-- subscriptions is created by the evaluator job; in deployments without an evaluator
-- (e.g. discovery-only stack) this table may not be present yet.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = 'public' AND table_name = 'subscriptions') THEN
        ALTER TABLE subscriptions
            ADD COLUMN IF NOT EXISTS subscriber_id UUID REFERENCES subscribers(subscriber_id);
    END IF;
END$$;
