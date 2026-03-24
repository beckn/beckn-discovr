-- Make item PK composite (id, bpp_id) to prevent cross-BPP data corruption.
-- Items from different BPPs may share the same id string; a single-column
-- PK would silently overwrite one BPP's data with another's.
ALTER TABLE item DROP CONSTRAINT item_pkey;
ALTER TABLE item ADD PRIMARY KEY (id, bpp_id);
