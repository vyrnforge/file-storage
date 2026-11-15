-- Logical recovery evidence is not proof that a remote write has stopped.
-- Retain the existing pin and never authorize physical deletion from this record alone.
CREATE TABLE fs.upload_terminal_recovery (
    session_id uuid PRIMARY KEY REFERENCES fs.upload_session(session_id),
    reason text NOT NULL CHECK (reason IN ('SESSION_EXPIRED','VERIFY_DEAD','SCAN_DEAD')),
    recovered_at timestamptz NOT NULL,
    cleanup_disposition text NOT NULL DEFAULT 'HOLD' CHECK (cleanup_disposition='HOLD')
);

CREATE FUNCTION fs.guard_terminal_recovery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'immutable terminal recovery evidence' USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fs.upload_session s
        JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.file_id=s.file_id AND v.version_id=s.version_id
        WHERE s.session_id=NEW.session_id
          AND ((s.state='INITIATED' AND v.state='PENDING') OR (s.state='VERIFYING' AND v.state='VERIFYING'))
          AND (
              (NEW.reason='SESSION_EXPIRED' AND
                  (s.expires_at<NEW.recovered_at OR (s.expires_at=NEW.recovered_at AND s.expires_nano_remainder=0)))
              OR (NEW.reason IN ('VERIFY_DEAD','SCAN_DEAD') AND EXISTS (
                  SELECT 1 FROM fs.storage_operation o
                  WHERE o.tenant_id=s.tenant_id AND o.file_id=s.file_id AND o.version_id=s.version_id
                    AND o.location_id=s.location_id AND o.operation_key=s.session_id::text AND o.status='DEAD'
                    AND ((NEW.reason='VERIFY_DEAD' AND s.state='INITIATED' AND o.kind='VERIFY_UPLOAD')
                      OR (NEW.reason='SCAN_DEAD' AND s.state='VERIFYING' AND o.kind='SCAN_UPLOAD'))
              ))
          )
          AND NOT EXISTS (
              SELECT 1 FROM fs.storage_operation o
              WHERE o.tenant_id=s.tenant_id AND o.file_id=s.file_id AND o.version_id=s.version_id
                AND o.location_id=s.location_id AND o.operation_key=s.session_id::text
                AND o.kind IN ('VERIFY_UPLOAD','SCAN_UPLOAD') AND o.status='PENDING'
          )
    ) THEN
        RAISE EXCEPTION 'terminal recovery requires expired session or dead work' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER upload_terminal_recovery_guard BEFORE INSERT OR UPDATE OR DELETE
    ON fs.upload_terminal_recovery FOR EACH ROW EXECUTE FUNCTION fs.guard_terminal_recovery();

CREATE INDEX upload_session_recovery_candidates ON fs.upload_session(expires_at, session_id)
    WHERE state IN ('INITIATED','VERIFYING');

CREATE OR REPLACE FUNCTION fs.guard_scan_version_state() RETURNS trigger LANGUAGE plpgsql AS $$
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
    ELSIF OLD.state IN ('PENDING','VERIFYING') AND NEW.state='FAILED' AND EXISTS (
        SELECT 1 FROM fs.upload_session s JOIN fs.upload_terminal_recovery x USING(session_id)
        WHERE s.tenant_id=NEW.tenant_id AND s.file_id=NEW.file_id AND s.version_id=NEW.version_id
          AND ((OLD.state='PENDING' AND s.state='INITIATED') OR (OLD.state='VERIFYING' AND s.state='VERIFYING'))
    ) THEN RETURN NEW;
    ELSIF OLD.state='VERIFYING' AND NEW.state='FAILED' AND EXISTS (
        SELECT 1 FROM fs.upload_session s JOIN fs.storage_operation o ON o.tenant_id=s.tenant_id AND o.version_id=s.version_id
        WHERE s.version_id=NEW.version_id AND o.kind='SCAN_UPLOAD' AND o.operation_key=s.session_id::text AND o.status='DEAD'
    ) THEN RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid scan version transition' USING ERRCODE='23514';
END;
$$;
CREATE OR REPLACE FUNCTION fs.guard_scan_session_state() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.state='INITIATED' THEN RETURN NEW; END IF;
    ELSIF NEW.state=OLD.state THEN RETURN NEW;
    ELSIF OLD.state IN ('INITIATED','VERIFYING') AND NEW.state IN ('FAILED','EXPIRED') AND EXISTS (
        SELECT 1 FROM fs.upload_terminal_recovery x
        JOIN fs.file_version v ON v.version_id=NEW.version_id AND v.tenant_id=NEW.tenant_id AND v.file_id=NEW.file_id
        WHERE x.session_id=NEW.session_id AND v.state='FAILED'
          AND NEW.state=CASE WHEN x.reason='SESSION_EXPIRED' THEN 'EXPIRED' ELSE 'FAILED' END
    ) THEN RETURN NEW;
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
