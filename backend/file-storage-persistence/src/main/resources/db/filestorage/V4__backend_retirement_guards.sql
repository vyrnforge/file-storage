-- Conservative backend-wide retirement. Existing version/job rows do not pin
-- a credential revision; retain every revision until the backend has no references.
CREATE FUNCTION fs.backend_retirement_blocked(id uuid) RETURNS boolean
LANGUAGE sql VOLATILE AS $$
    SELECT EXISTS (SELECT 1 FROM fs.backend_revision WHERE backend_id=id AND status='ACTIVE')
        OR EXISTS (SELECT 1 FROM fs.file_version v JOIN fs.storage_location l USING (location_id) WHERE l.backend_id=id)
        OR EXISTS (SELECT 1 FROM fs.upload_session WHERE backend_id=id)
        OR EXISTS (SELECT 1 FROM fs.storage_operation o JOIN fs.storage_location l USING (location_id) WHERE l.backend_id=id)
        OR EXISTS (SELECT 1 FROM fs.active_policy a JOIN fs.policy_rule r
            USING (tenant_id, consumer, purpose, policy_revision)
            JOIN fs.storage_location l USING (location_id) WHERE l.backend_id=id);
$$;

-- Lock the same backend identity before inserting a physical reference.
-- Under repeatable read, a completed retirement's parent UPDATE causes a
-- serialization failure even when the transaction had an older snapshot.
CREATE FUNCTION fs.guard_new_storage_reference() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE target_backend uuid;
BEGIN
    SELECT b.backend_id INTO target_backend
        FROM fs.storage_location l JOIN fs.backend b USING (backend_id)
        WHERE l.location_id=NEW.location_id FOR SHARE OF b;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'missing storage location' USING ERRCODE = '23503';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM fs.backend_revision WHERE backend_id=target_backend
                   AND status IN ('ACTIVE','DRAINING')) THEN
        RAISE EXCEPTION 'backend cannot accept references' USING ERRCODE = '23514';
    END IF;
    IF TG_TABLE_NAME = 'upload_session' THEN
        IF NOT EXISTS (SELECT 1 FROM fs.backend_revision WHERE backend_id=target_backend
                       AND config_revision=NEW.config_revision AND status IN ('ACTIVE','DRAINING')) THEN
            RAISE EXCEPTION 'revision cannot accept references' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER file_version_retirement_fence BEFORE INSERT OR UPDATE OF location_id
    ON fs.file_version FOR EACH ROW EXECUTE FUNCTION fs.guard_new_storage_reference();
CREATE TRIGGER upload_session_retirement_fence BEFORE INSERT OR UPDATE OF location_id, backend_id, config_revision
    ON fs.upload_session FOR EACH ROW EXECUTE FUNCTION fs.guard_new_storage_reference();

-- Policy publication is also a reference admission. Keep historical rules,
-- but do not publish a pointer referencing a fully retired backend.
CREATE FUNCTION fs.guard_policy_retirement() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE target_backend uuid;
BEGIN
    FOR target_backend IN
        SELECT DISTINCT l.backend_id FROM fs.policy_rule r JOIN fs.storage_location l USING (location_id)
        WHERE r.tenant_id=NEW.tenant_id AND r.consumer=NEW.consumer
          AND r.purpose=NEW.purpose AND r.policy_revision=NEW.policy_revision
        ORDER BY l.backend_id
    LOOP
        PERFORM 1 FROM fs.backend WHERE backend_id=target_backend FOR SHARE;
        IF EXISTS (SELECT 1 FROM fs.backend_revision WHERE backend_id=target_backend AND status='RETIRED')
           AND NOT EXISTS (SELECT 1 FROM fs.backend_revision WHERE backend_id=target_backend
                           AND status IN ('ACTIVE','DRAINING')) THEN
            RAISE EXCEPTION 'policy references retired backend' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER active_policy_retirement_fence BEFORE INSERT OR UPDATE ON fs.active_policy
    FOR EACH ROW EXECUTE FUNCTION fs.guard_policy_retirement();

CREATE OR REPLACE FUNCTION fs.guard_backend_revision() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- Shared lock order with the activation adapter. Historical payload never changes.
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'backend history cannot be deleted' USING ERRCODE = '23514';
    END IF;
    PERFORM 1 FROM fs.backend WHERE backend_id = NEW.backend_id FOR UPDATE;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.validated_revision IS NOT NULL THEN
            RAISE EXCEPTION 'new backend revision must be an unvalidated draft' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.backend_id, NEW.config_revision, NEW.credential_ref)
        IS DISTINCT FROM (OLD.backend_id, OLD.config_revision, OLD.credential_ref) THEN
        RAISE EXCEPTION 'backend revision payload is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW IS NOT DISTINCT FROM OLD THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'DRAFT'
        AND OLD.validated_revision IS NULL AND NEW.validated_revision = NEW.config_revision THEN
        RETURN NEW;
    END IF;
    IF NEW.validated_revision IS NOT DISTINCT FROM OLD.validated_revision THEN
        IF OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE'
            AND OLD.validated_revision = OLD.config_revision THEN
            IF EXISTS (SELECT 1 FROM fs.backend_revision
                WHERE backend_id = NEW.backend_id AND config_revision >= NEW.config_revision
                  AND status <> 'DRAFT') THEN
                RAISE EXCEPTION 'backend activation must advance' USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
        IF OLD.status = 'ACTIVE' AND NEW.status = 'DRAINING' THEN
            RETURN NEW;
        END IF;
    END IF;
    IF OLD.status = 'DRAINING' AND NEW.status = 'RETIRED'
        AND NEW.validated_revision IS NOT DISTINCT FROM OLD.validated_revision THEN
        -- The parent row lock serializes all reference admission. A physical
        -- no-op UPDATE changes its MVCC version, fencing older snapshots.
        IF current_setting('transaction_isolation') <> 'read committed' THEN
            RAISE EXCEPTION 'retirement requires read committed' USING ERRCODE = '23514';
        END IF;
        IF fs.backend_retirement_blocked(NEW.backend_id) THEN
            RAISE EXCEPTION 'backend retirement blocked' USING ERRCODE = '23514';
        END IF;
        UPDATE fs.backend SET endpoint=endpoint WHERE backend_id=NEW.backend_id;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'invalid backend revision transition' USING ERRCODE = '23514';
END;
$$;

