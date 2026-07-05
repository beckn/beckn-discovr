-- Exception-safe timestamptz parse used by the discover active/validity filter.
--
-- The /discover ?validity predicate compares catalog validity dates (stored as text in the item
-- payload JSONB) against "now". A raw ::timestamptz cast throws — and thus turns the whole query
-- into a 500 — on any value PostgreSQL cannot parse: malformed ('2020-01-01garbage'), out-of-range
-- ('2020-13-45'), or non-date. This helper returns NULL for any such value instead of raising, so
-- the filter treats it as "no usable bound" (the catalog counts as valid) rather than failing the
-- request. NULL input returns NULL (no exception).
CREATE OR REPLACE FUNCTION try_to_timestamptz(txt text)
RETURNS timestamptz
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
BEGIN
    RETURN txt::timestamptz;
EXCEPTION WHEN others THEN
    RETURN NULL;
END;
$$;
