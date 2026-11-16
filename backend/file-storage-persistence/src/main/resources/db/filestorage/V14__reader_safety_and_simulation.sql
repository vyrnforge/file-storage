-- Reader leases are observational: expiry is NOT evidence of drain.
CREATE TABLE fs.download_reader (
    handle_hash bytea PRIMARY KEY REFERENCES fs.download_transfer_handle(handle_hash),
    lease_token uuid UNIQUE,
    claimed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at>claimed_at),
    drained_at timestamptz,
    CHECK (drained_at IS NULL OR (lease_token IS NOT NULL AND drained_at>=claimed_at))
);
-- Existing claims cannot be retroactively acknowledged. Unknown readers remain blockers.
INSERT INTO fs.download_reader(handle_hash,claimed_at,expires_at)
    SELECT handle_hash,claimed_at,expires_at FROM fs.download_transfer_handle WHERE claimed_at IS NOT NULL;

CREATE FUNCTION fs.guard_download_reader() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'reader evidence retained' USING ERRCODE='23514';
    ELSIF TG_OP='INSERT' THEN
        IF NEW.lease_token IS NULL OR NEW.drained_at IS NOT NULL OR NOT EXISTS (
            SELECT 1 FROM fs.download_transfer_handle h WHERE h.handle_hash=NEW.handle_hash
              AND h.claimed_at=NEW.claimed_at AND h.expires_at=NEW.expires_at
        ) THEN RAISE EXCEPTION 'invalid reader lease' USING ERRCODE='23514'; END IF;
    ELSIF NEW.handle_hash IS DISTINCT FROM OLD.handle_hash OR NEW.lease_token IS DISTINCT FROM OLD.lease_token
        OR NEW.claimed_at IS DISTINCT FROM OLD.claimed_at OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
        OR OLD.drained_at IS NOT NULL OR NEW.drained_at IS NULL OR OLD.lease_token IS NULL THEN
        RAISE EXCEPTION 'immutable reader evidence' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER download_reader_guard BEFORE INSERT OR UPDATE OR DELETE ON fs.download_reader
    FOR EACH ROW EXECUTE FUNCTION fs.guard_download_reader();
CREATE INDEX download_claimed_file ON fs.download_transfer_handle(tenant_id,file_id) WHERE claimed_at IS NOT NULL;

-- A separate simulation ledger cannot be consumed by the executable operation queue.
CREATE TABLE fs.purge_simulation (
    operation_id uuid PRIMARY KEY REFERENCES fs.purge_preparation(operation_id),
    claim_token uuid NOT NULL UNIQUE,
    attempts integer NOT NULL CHECK (attempts BETWEEN 1 AND 10),
    max_attempts integer NOT NULL CHECK (max_attempts BETWEEN attempts AND 10),
    claimed_at timestamptz NOT NULL,
    locked_until timestamptz NOT NULL CHECK (locked_until>claimed_at AND locked_until<=claimed_at+interval '5 minutes'),
    status text NOT NULL CHECK (status IN ('CLAIMED','SIMULATED')),
    finished_at timestamptz,
    CHECK ((status='SIMULATED')=(finished_at IS NOT NULL)),
    CHECK (finished_at IS NULL OR (finished_at>=claimed_at AND finished_at<locked_until))
);
