-- Scan work reuses the V3 durable operation ledger, not a new queue.
ALTER TABLE fs.storage_operation DROP CONSTRAINT storage_operation_kind_check;
ALTER TABLE fs.storage_operation ADD CONSTRAINT storage_operation_kind_check
    CHECK (kind IN ('VERIFY_UPLOAD','SCAN_UPLOAD','CLEANUP_UPLOAD','PURGE_VERSION','MIGRATE_VERSION'));
ALTER TABLE fs.file_version DROP CONSTRAINT file_version_state_check;
ALTER TABLE fs.file_version ADD CONSTRAINT file_version_state_check
    CHECK (state IN ('PENDING','VERIFYING','AVAILABLE','QUARANTINED','FAILED'));
ALTER TABLE fs.upload_session DROP CONSTRAINT upload_session_state_check;
ALTER TABLE fs.upload_session ADD CONSTRAINT upload_session_state_check
    CHECK (state IN ('INITIATED','VERIFYING','COMPLETED','FAILED','EXPIRED'));

CREATE TABLE fs.upload_scan_evidence (
    session_id uuid PRIMARY KEY REFERENCES fs.upload_integrity_verification(session_id),
    operation_id uuid NOT NULL UNIQUE REFERENCES fs.storage_operation(operation_id),
    verdict text NOT NULL CHECK (verdict IN ('CLEAN','MALICIOUS')),
    observed_size bigint NOT NULL CHECK (observed_size >= 0),
    observed_sha256 varchar(64) NOT NULL CHECK (observed_sha256 ~ '^[0-9a-f]{64}$'),
    detected_mime_type varchar(255) NOT NULL CHECK (btrim(detected_mime_type) <> ''),
    scanned_at timestamptz NOT NULL
);

CREATE FUNCTION fs.guard_scan_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'immutable scan evidence' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fs.upload_integrity_verification i
        JOIN fs.upload_session s USING(session_id)
        JOIN fs.file_version v ON v.version_id=s.version_id
        JOIN fs.initiation_request r ON r.session_id=s.session_id
        JOIN fs.storage_operation o ON o.operation_id=NEW.operation_id
        WHERE i.session_id=NEW.session_id AND i.verified_at IS NOT NULL
          AND i.observed_size=NEW.observed_size AND i.observed_sha256=NEW.observed_sha256
          AND NEW.scanned_at>=i.verified_at
          AND s.state='VERIFYING' AND v.state='VERIFYING'
          AND o.kind='SCAN_UPLOAD' AND o.status='SUCCEEDED'
          AND o.tenant_id=s.tenant_id AND o.file_id=s.file_id
          AND o.version_id=s.version_id AND o.location_id=s.location_id
          AND o.operation_key=s.session_id::text
          AND (NEW.verdict='MALICIOUS' OR NEW.detected_mime_type=r.declared_mime_type)
    ) THEN
        RAISE EXCEPTION 'scan evidence not bound to verified job' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER upload_scan_evidence_guard BEFORE INSERT OR UPDATE OR DELETE
    ON fs.upload_scan_evidence FOR EACH ROW EXECUTE FUNCTION fs.guard_scan_evidence();

CREATE FUNCTION fs.guard_scan_version_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state='PENDING' THEN RETURN NEW; END IF;
    ELSIF NEW.state=OLD.state THEN RETURN NEW;
    ELSIF OLD.state='PENDING' AND NEW.state='VERIFYING' AND EXISTS (
        SELECT 1 FROM fs.upload_session s JOIN fs.upload_integrity_verification i USING(session_id)
        JOIN fs.storage_operation o ON o.tenant_id=s.tenant_id AND o.version_id=s.version_id
        WHERE s.version_id=NEW.version_id AND i.verified_at IS NOT NULL AND o.kind='SCAN_UPLOAD'
          AND o.operation_key=s.session_id::text AND o.status='PENDING'
    ) THEN RETURN NEW;
    ELSIF OLD.state='VERIFYING' AND NEW.state IN ('AVAILABLE','QUARANTINED') AND EXISTS (
        SELECT 1 FROM fs.upload_session s JOIN fs.upload_scan_evidence e USING(session_id)
        WHERE s.version_id=NEW.version_id AND e.verdict=CASE WHEN NEW.state='AVAILABLE' THEN 'CLEAN' ELSE 'MALICIOUS' END
    ) THEN RETURN NEW;
    ELSIF OLD.state='VERIFYING' AND NEW.state='FAILED' AND EXISTS (
        SELECT 1 FROM fs.upload_session s JOIN fs.storage_operation o ON o.tenant_id=s.tenant_id AND o.version_id=s.version_id
        WHERE s.version_id=NEW.version_id AND o.kind='SCAN_UPLOAD' AND o.operation_key=s.session_id::text AND o.status='DEAD'
    ) THEN RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid scan version transition' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER scan_version_state_guard BEFORE INSERT OR UPDATE OF state ON fs.file_version
    FOR EACH ROW EXECUTE FUNCTION fs.guard_scan_version_state();

CREATE FUNCTION fs.guard_scan_session_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state='INITIATED' THEN RETURN NEW; END IF;
    ELSIF NEW.state=OLD.state THEN RETURN NEW;
    ELSIF EXISTS (
        SELECT 1 FROM fs.file_version v WHERE v.version_id=NEW.version_id
          AND ((OLD.state='INITIATED' AND NEW.state='VERIFYING' AND v.state='VERIFYING')
            OR (OLD.state='VERIFYING' AND NEW.state='COMPLETED' AND v.state='AVAILABLE')
            OR (OLD.state='VERIFYING' AND NEW.state='FAILED' AND v.state IN ('QUARANTINED','FAILED'))
            OR (OLD.state='VERIFYING' AND NEW.state='EXPIRED' AND v.state='FAILED'))
    ) THEN RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid scan session transition' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER scan_session_state_guard BEFORE INSERT OR UPDATE OF state ON fs.upload_session
    FOR EACH ROW EXECUTE FUNCTION fs.guard_scan_session_state();
