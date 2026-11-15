-- Durable first integrity request and immutable matching evidence. Not scan/availability.
CREATE TABLE fs.upload_integrity_verification (
    session_id uuid PRIMARY KEY REFERENCES fs.upload_transfer_handle(session_id),
    expected_sha256 varchar(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9a-f]{64}$'),
    requested_at timestamptz NOT NULL,
    verified_at timestamptz,
    observed_size bigint CHECK (observed_size >= 0),
    observed_sha256 varchar(64),
    CHECK ((verified_at IS NULL AND observed_size IS NULL AND observed_sha256 IS NULL)
        OR (verified_at IS NOT NULL AND observed_size IS NOT NULL AND observed_sha256 IS NOT NULL
            AND observed_sha256 = expected_sha256 AND verified_at >= requested_at))
);

-- A request is immutable. One monotonic transition to matching evidence.
CREATE FUNCTION fs.guard_upload_integrity_verification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.session_id IS DISTINCT FROM OLD.session_id
       OR NEW.expected_sha256 IS DISTINCT FROM OLD.expected_sha256
       OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
       OR OLD.verified_at IS NOT NULL OR NEW.verified_at IS NULL THEN
        RAISE EXCEPTION 'immutable integrity evidence' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER upload_integrity_verification_guard BEFORE UPDATE ON fs.upload_integrity_verification
    FOR EACH ROW EXECUTE FUNCTION fs.guard_upload_integrity_verification();
