-- Isolated opt-in only. A durable write fence is required under REPEATABLE READ.
CREATE TABLE fs.isolated_quota_fence (
 tenant_id uuid PRIMARY KEY, epoch bigint NOT NULL DEFAULT 0 CHECK(epoch>=0),
 enabled_revision bigint,
 FOREIGN KEY(tenant_id,enabled_revision) REFERENCES fs.isolated_policy_activation(tenant_id,revision)
);
CREATE FUNCTION fs.guard_quota_fence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'quota fence retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.tenant_id<>OLD.tenant_id OR NEW.epoch<OLD.epoch OR
     (OLD.enabled_revision IS NOT NULL AND NEW.enabled_revision IS DISTINCT FROM OLD.enabled_revision)
  THEN RAISE EXCEPTION 'immutable quota integration' USING ERRCODE='23514'; END IF;
  IF NEW.enabled_revision IS NOT DISTINCT FROM OLD.enabled_revision THEN RETURN NEW; END IF;
 END IF;
 IF NEW.enabled_revision IS NOT NULL AND (
    NEW.enabled_revision IS DISTINCT FROM (SELECT max(revision) FROM fs.isolated_policy_activation WHERE tenant_id=NEW.tenant_id)
    OR EXISTS(SELECT 1 FROM fs.file WHERE tenant_id=NEW.tenant_id)
    OR EXISTS(SELECT 1 FROM fs.upload_session WHERE tenant_id=NEW.tenant_id)
    OR EXISTS(SELECT 1 FROM fs.isolated_quota_reservation WHERE tenant_id=NEW.tenant_id)
 ) THEN RAISE EXCEPTION 'quota integration requires empty tenant and current activation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER quota_fence_guard BEFORE INSERT OR UPDATE OR DELETE ON fs.isolated_quota_fence
 FOR EACH ROW EXECUTE FUNCTION fs.guard_quota_fence();

CREATE FUNCTION fs.touch_quota_fence(t uuid) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended(t::text,3105));
 INSERT INTO fs.isolated_quota_fence(tenant_id) VALUES(t)
 ON CONFLICT(tenant_id) DO UPDATE SET epoch=fs.isolated_quota_fence.epoch+1;
END; $$;

-- Activation and initiation share the durable fence; stale writers abort.
CREATE FUNCTION fs.fence_quota_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM fs.touch_quota_fence(NEW.tenant_id);
 IF EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=NEW.tenant_id AND enabled_revision IS NOT NULL) THEN
  IF NEW.revision <= COALESCE((SELECT max(revision) FROM fs.isolated_policy_activation WHERE tenant_id=NEW.tenant_id),0)
     OR NEW.quota_limit < (SELECT COALESCE(sum(bytes),0) FROM fs.isolated_quota_reservation WHERE tenant_id=NEW.tenant_id AND status<>'RELEASED')
     OR NOT EXISTS(SELECT 1 FROM fs.retention_policy_draft d JOIN fs.retention_policy_review r USING(tenant_id,revision)
        WHERE d.tenant_id=NEW.tenant_id AND d.revision=NEW.revision
          AND d.revision=(SELECT max(revision) FROM fs.retention_policy_draft WHERE tenant_id=NEW.tenant_id)
          AND d.quota_mode='CHARGE_UNTIL_VERIFIED_PURGE' AND NEW.actor<>d.author AND NEW.actor<>r.reviewer
          AND r.reviewed_at<=NEW.activated_at
          AND NOT EXISTS(SELECT 1 FROM fs.isolated_policy_activation a JOIN fs.retention_policy_draft prior USING(tenant_id,revision)
             WHERE a.tenant_id=NEW.tenant_id AND (a.activated_at>NEW.activated_at OR prior.retention_seconds>d.retention_seconds)))
  THEN RAISE EXCEPTION 'invalid integrated quota activation' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER integrated_quota_activation_fence BEFORE INSERT ON fs.isolated_policy_activation
 FOR EACH ROW EXECUTE FUNCTION fs.fence_quota_activation();

CREATE FUNCTION fs.fence_quota_session_insert() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM fs.touch_quota_fence(NEW.tenant_id);
 RETURN NEW;
END; $$;
CREATE TRIGGER integrated_quota_session_fence AFTER INSERT ON fs.upload_session
 FOR EACH ROW EXECUTE FUNCTION fs.fence_quota_session_insert();

-- Defense in depth for ledger inserts; V16 validates exact pins.
CREATE FUNCTION fs.guard_integrated_quota_insert() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active_revision bigint; active_limit bigint;
BEGIN
 PERFORM fs.touch_quota_fence(NEW.tenant_id);
 IF NOT EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=NEW.tenant_id AND enabled_revision IS NOT NULL)
 THEN RETURN NEW; END IF;
 SELECT revision,quota_limit INTO active_revision,active_limit FROM fs.isolated_policy_activation
 WHERE tenant_id=NEW.tenant_id ORDER BY revision DESC LIMIT 1;
 IF NEW.policy_revision IS DISTINCT FROM active_revision
    OR EXISTS(SELECT 1 FROM fs.upload_transfer_handle WHERE session_id=NEW.session_id AND claimed_at IS NOT NULL)
    OR (SELECT COALESCE(sum(bytes),0) FROM fs.isolated_quota_reservation WHERE tenant_id=NEW.tenant_id AND status<>'RELEASED')+NEW.bytes>active_limit
 THEN RAISE EXCEPTION 'isolated quota unavailable' USING ERRCODE='PQU01'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER integrated_quota_insert_guard BEFORE INSERT ON fs.isolated_quota_reservation
 FOR EACH ROW EXECUTE FUNCTION fs.guard_integrated_quota_insert();

-- Existing Kernel idempotent transaction inserts request after session/pin.
CREATE FUNCTION fs.reserve_integrated_quota() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE active_revision bigint;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=NEW.tenant_id AND enabled_revision IS NOT NULL)
 THEN RETURN NEW; END IF;
 SELECT revision INTO STRICT active_revision FROM fs.isolated_policy_activation
 WHERE tenant_id=NEW.tenant_id ORDER BY revision DESC LIMIT 1;
 INSERT INTO fs.isolated_quota_reservation(session_id,tenant_id,file_id,version_id,location_id,consumer,
    policy_revision,bytes,status,created_at)
 SELECT s.session_id,s.tenant_id,s.file_id,s.version_id,s.location_id,s.consumer,
    active_revision,NEW.expected_size,'RESERVED',transaction_timestamp()
 FROM fs.upload_session s WHERE s.tenant_id=NEW.tenant_id AND s.session_id=NEW.session_id;
 RETURN NEW;
END; $$;
CREATE TRIGGER integrated_quota_reserve AFTER INSERT ON fs.initiation_request
 FOR EACH ROW EXECUTE FUNCTION fs.reserve_integrated_quota();

CREATE FUNCTION fs.settle_integrated_quota() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target text;
BEGIN
 IF NEW.state=OLD.state OR NEW.state NOT IN ('COMPLETED','FAILED','EXPIRED')
    OR NOT EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=NEW.tenant_id AND enabled_revision IS NOT NULL)
 THEN RETURN NEW; END IF;
 PERFORM fs.touch_quota_fence(NEW.tenant_id);
 IF NEW.state='COMPLETED' THEN target:='CHARGED';
 ELSIF NOT EXISTS(SELECT 1 FROM fs.upload_transfer_handle WHERE session_id=NEW.session_id AND claimed_at IS NOT NULL)
 THEN target:='RELEASED';
 ELSE RETURN NEW; END IF;
 UPDATE fs.isolated_quota_reservation SET status=target,finished_at=GREATEST(created_at,transaction_timestamp())
 WHERE tenant_id=NEW.tenant_id AND session_id=NEW.session_id AND status='RESERVED';
 IF NOT FOUND THEN RAISE EXCEPTION 'missing integrated reservation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER integrated_quota_settle AFTER UPDATE OF state ON fs.upload_session
 FOR EACH ROW EXECUTE FUNCTION fs.settle_integrated_quota();

-- Check final state, not intermediate state between session and request.
CREATE FUNCTION fs.check_integrated_quota_session() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=NEW.tenant_id AND enabled_revision IS NOT NULL)
 THEN RETURN NEW; END IF;
 IF NOT EXISTS(SELECT 1 FROM fs.upload_session s
    JOIN fs.initiation_request r ON r.session_id=s.session_id AND r.tenant_id=s.tenant_id
    JOIN fs.isolated_quota_reservation q ON q.session_id=s.session_id AND q.tenant_id=s.tenant_id
    WHERE s.tenant_id=NEW.tenant_id AND s.session_id=NEW.session_id
      AND q.file_id=s.file_id AND q.version_id=s.version_id AND q.location_id=s.location_id
      AND q.consumer=s.consumer AND q.bytes=r.expected_size
      AND q.status=CASE WHEN s.state='COMPLETED' THEN 'CHARGED'
        WHEN s.state IN ('FAILED','EXPIRED') AND NOT EXISTS(SELECT 1 FROM fs.upload_transfer_handle h
             WHERE h.session_id=s.session_id AND h.claimed_at IS NOT NULL) THEN 'RELEASED'
        ELSE 'RESERVED' END)
 THEN RAISE EXCEPTION 'integrated quota session mismatch' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE CONSTRAINT TRIGGER integrated_quota_session_check AFTER INSERT OR UPDATE ON fs.upload_session
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fs.check_integrated_quota_session();

CREATE FUNCTION fs.guard_integrated_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM fs.isolated_quota_fence WHERE tenant_id=OLD.tenant_id AND enabled_revision IS NOT NULL)
 THEN RAISE EXCEPTION 'integrated quota request immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER integrated_quota_request_guard BEFORE UPDATE OR DELETE ON fs.initiation_request
 FOR EACH ROW EXECUTE FUNCTION fs.guard_integrated_request();
