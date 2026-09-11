-- Exception-safe numeric/boolean parses used by the RFC 9535 JSONPath filter compiler
-- (jobs/catalog-discover-job .../rfc9535/FilterPredicateCompiler).
--
-- A filter comparison like `@.name < 5` picks its SQL cast from the shape of the literal on
-- the right-hand side (5 -> numeric), not from the actual JSON type of the field being
-- compared. A raw `::numeric` / `::boolean` cast throws -- and thus turns the whole discover
-- query into a swallowed exception (see beckn-discovr#471 review discussion) -- whenever the
-- field's real value doesn't parse as that type (e.g. comparing a string like "Wireless
-- Mouse" against a numeric literal). These helpers return NULL for any such value instead of
-- raising, so the comparison safely evaluates to "no match" -- which is also the RFC 9535
-- -correct behavior per section 2.3.5.2.2: a comparison between different basic JSON value
-- types is always false, never an error. NULL input returns NULL (no exception), mirroring
-- try_to_timestamptz (V6).
CREATE OR REPLACE FUNCTION try_to_numeric(txt text)
RETURNS numeric
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
BEGIN
    RETURN txt::numeric;
EXCEPTION WHEN others THEN
    RETURN NULL;
END;
$$;

CREATE OR REPLACE FUNCTION try_to_boolean(txt text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
BEGIN
    RETURN txt::boolean;
EXCEPTION WHEN others THEN
    RETURN NULL;
END;
$$;
