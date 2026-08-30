-- Exception-safe time-of-day parse used by the discover ?validity filter's startTime/endTime
-- fallback (applied only when a catalog's validity has no startDate/endDate — see V6 for the
-- date-based twin). A raw ::time cast throws on malformed or out-of-range input, which would
-- turn the whole /discover query into a 500. This helper returns NULL for any such value instead
-- of raising, so the filter treats it as "no usable bound" (the catalog counts as valid) rather
-- than failing the request. NULL input returns NULL (no exception).
CREATE OR REPLACE FUNCTION try_to_time(txt text)
RETURNS time
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
BEGIN
    RETURN txt::time;
EXCEPTION WHEN others THEN
    RETURN NULL;
END;
$$;
