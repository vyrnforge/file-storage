-- Preserve V1 history. All existing revisions are historical snapshots.
ALTER TABLE fs.policy_revision ADD COLUMN sealed boolean NOT NULL DEFAULT false;
UPDATE fs.policy_revision SET sealed = true;

CREATE FUNCTION fs.guard_policy_revision() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.sealed THEN
            RAISE EXCEPTION 'policy must be assembled before sealing' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'policy history cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF OLD.sealed OR NOT NEW.sealed
        OR (NEW.tenant_id, NEW.consumer, NEW.purpose, NEW.policy_revision)
            IS DISTINCT FROM (OLD.tenant_id, OLD.consumer, OLD.purpose, OLD.policy_revision) THEN
        RAISE EXCEPTION 'policy revision is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_policy_revision
    BEFORE INSERT OR UPDATE OR DELETE ON fs.policy_revision
    FOR EACH ROW EXECUTE FUNCTION fs.guard_policy_revision();

CREATE FUNCTION fs.guard_policy_rule() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    is_sealed boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'policy rule is immutable' USING ERRCODE = '23514';
    END IF;
    -- Serialize assembly against sealing, including inserts from other transactions.
    SELECT sealed INTO is_sealed FROM fs.policy_revision
        WHERE tenant_id = NEW.tenant_id AND consumer = NEW.consumer
          AND purpose = NEW.purpose AND policy_revision = NEW.policy_revision
        FOR UPDATE;
    IF NOT FOUND OR is_sealed THEN
        RAISE EXCEPTION 'policy revision is missing or sealed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_policy_rule
    BEFORE INSERT OR UPDATE OR DELETE ON fs.policy_rule
    FOR EACH ROW EXECUTE FUNCTION fs.guard_policy_rule();

CREATE FUNCTION fs.guard_active_policy() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'active policy cannot be deleted' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.tenant_id, NEW.consumer, NEW.purpose)
            IS DISTINCT FROM (OLD.tenant_id, OLD.consumer, OLD.purpose)
            OR NEW.policy_revision <= OLD.policy_revision THEN
            RAISE EXCEPTION 'policy pointer must advance' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM fs.policy_revision
        WHERE tenant_id = NEW.tenant_id AND consumer = NEW.consumer
          AND purpose = NEW.purpose AND policy_revision = NEW.policy_revision AND sealed
    ) THEN
        RAISE EXCEPTION 'policy must be sealed' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER active_policy_guard
    BEFORE INSERT OR UPDATE OR DELETE ON fs.active_policy
    FOR EACH ROW EXECUTE FUNCTION fs.guard_active_policy();

CREATE FUNCTION fs.guard_backend_revision() RETURNS trigger
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
            RETURN NEW;
        END IF;
        IF OLD.status = 'ACTIVE' AND NEW.status = 'DRAINING' THEN
            RETURN NEW;
        END IF;
    END IF;
    -- Retirement requires reference/job guards in FS-210B, not a raw state update.
    RAISE EXCEPTION 'invalid backend revision transition' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER backend_revision_guard
    BEFORE INSERT OR UPDATE OR DELETE ON fs.backend_revision
    FOR EACH ROW EXECUTE FUNCTION fs.guard_backend_revision();
