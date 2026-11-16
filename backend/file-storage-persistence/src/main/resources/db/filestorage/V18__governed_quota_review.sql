-- Isolated reviewed quota values, bound to the existing retention revision.
CREATE TABLE fs.isolated_quota_policy_draft (
 tenant_id uuid NOT NULL, revision bigint NOT NULL, expected_active bigint NOT NULL CHECK(expected_active>=0),
 quota_limit bigint NOT NULL CHECK(quota_limit>0), evidence_ttl_seconds bigint NOT NULL CHECK(evidence_ttl_seconds BETWEEN 1 AND 86400),
 author varchar(128) NOT NULL CHECK(btrim(author)<>''), drafted_at timestamptz NOT NULL,
 PRIMARY KEY(tenant_id,revision), CHECK(revision>expected_active),
 FOREIGN KEY(tenant_id,revision) REFERENCES fs.retention_policy_draft(tenant_id,revision)
);
CREATE TABLE fs.isolated_quota_policy_review (
 tenant_id uuid NOT NULL, revision bigint NOT NULL, reviewer varchar(128) NOT NULL CHECK(btrim(reviewer)<>''),
 reviewed_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,revision),
 FOREIGN KEY(tenant_id,revision) REFERENCES fs.isolated_quota_policy_draft(tenant_id,revision)
);
CREATE TRIGGER quota_policy_draft_immutable BEFORE UPDATE OR DELETE ON fs.isolated_quota_policy_draft
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER quota_policy_review_immutable BEFORE UPDATE OR DELETE ON fs.isolated_quota_policy_review
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();

-- Bound lock waits in the existing database fence. Function-local setting is restored on return.
CREATE OR REPLACE FUNCTION fs.touch_quota_fence(t uuid) RETURNS void LANGUAGE plpgsql
 SET lock_timeout='2s' AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(hashtextextended(t::text,3105));
 INSERT INTO fs.isolated_quota_fence(tenant_id) VALUES(t)
 ON CONFLICT(tenant_id) DO UPDATE SET epoch=fs.isolated_quota_fence.epoch+1;
END; $$;

CREATE FUNCTION fs.guard_quota_policy_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM fs.touch_quota_fence(NEW.tenant_id);
 IF NEW.expected_active<>COALESCE((SELECT max(revision) FROM fs.isolated_policy_activation WHERE tenant_id=NEW.tenant_id),0)
    OR NEW.revision IS DISTINCT FROM (SELECT max(revision) FROM fs.retention_policy_draft WHERE tenant_id=NEW.tenant_id)
    OR NOT EXISTS(SELECT 1 FROM fs.retention_policy_draft d WHERE d.tenant_id=NEW.tenant_id AND d.revision=NEW.revision
      AND d.quota_mode='CHARGE_UNTIL_VERIFIED_PURGE' AND d.created_at<=NEW.drafted_at)
 THEN RAISE EXCEPTION 'stale quota policy draft' USING ERRCODE='PQU01'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER quota_policy_draft_guard BEFORE INSERT ON fs.isolated_quota_policy_draft
 FOR EACH ROW EXECUTE FUNCTION fs.guard_quota_policy_draft();

CREATE FUNCTION fs.guard_quota_policy_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM fs.touch_quota_fence(NEW.tenant_id);
 IF NOT EXISTS(SELECT 1 FROM fs.isolated_quota_policy_draft q
   WHERE q.tenant_id=NEW.tenant_id AND q.revision=NEW.revision AND q.author<>NEW.reviewer AND q.drafted_at<=NEW.reviewed_at
     AND q.expected_active=COALESCE((SELECT max(revision) FROM fs.isolated_policy_activation WHERE tenant_id=NEW.tenant_id),0)
     AND q.revision=(SELECT max(revision) FROM fs.retention_policy_draft WHERE tenant_id=NEW.tenant_id))
 THEN RAISE EXCEPTION 'invalid independent quota review' USING ERRCODE='PQU01'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER quota_policy_review_guard BEFORE INSERT ON fs.isolated_quota_policy_review
 FOR EACH ROW EXECUTE FUNCTION fs.guard_quota_policy_review();

-- Runs after V17's integrated_quota_activation_fence, which acquires the durable fence.
-- All new activations require reviewed exact values. Existing V16/V17 rows are
-- retained as history, never silently upgraded to reviewed policy.
CREATE FUNCTION fs.guard_reviewed_quota_activation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(
   SELECT 1 FROM fs.isolated_quota_policy_draft q JOIN fs.isolated_quota_policy_review qr USING(tenant_id,revision)
   JOIN fs.retention_policy_draft d USING(tenant_id,revision) JOIN fs.retention_policy_review dr USING(tenant_id,revision)
   WHERE q.tenant_id=NEW.tenant_id AND q.revision=NEW.revision
     AND q.expected_active=COALESCE((SELECT max(revision) FROM fs.isolated_policy_activation WHERE tenant_id=NEW.tenant_id),0)
     AND q.revision=(SELECT max(revision) FROM fs.retention_policy_draft WHERE tenant_id=NEW.tenant_id)
     AND q.quota_limit=NEW.quota_limit AND q.evidence_ttl_seconds=NEW.evidence_ttl_seconds
     AND NEW.actor<>q.author AND NEW.actor<>qr.reviewer AND NEW.actor<>d.author AND NEW.actor<>dr.reviewer
     AND NEW.activated_at>=qr.reviewed_at AND NEW.activated_at>=dr.reviewed_at
     AND d.quota_mode='CHARGE_UNTIL_VERIFIED_PURGE'
     AND NOT EXISTS(SELECT 1 FROM fs.isolated_policy_activation a JOIN fs.retention_policy_draft prior USING(tenant_id,revision)
       WHERE a.tenant_id=NEW.tenant_id AND (a.activated_at>NEW.activated_at OR prior.retention_seconds>d.retention_seconds))
 ) OR NEW.quota_limit<(SELECT COALESCE(sum(bytes),0) FROM fs.isolated_quota_reservation WHERE tenant_id=NEW.tenant_id AND status<>'RELEASED')
 THEN RAISE EXCEPTION 'reviewed quota activation required' USING ERRCODE='PQU01'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER reviewed_quota_activation_guard BEFORE INSERT ON fs.isolated_policy_activation
 FOR EACH ROW EXECUTE FUNCTION fs.guard_reviewed_quota_activation();

-- New integration enablement must bind to a reviewed current activation; old enabled rows
-- are preserved, but a legacy non-integrated activation cannot be used to opt in.
CREATE FUNCTION fs.guard_reviewed_quota_enablement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.enabled_revision IS NULL THEN RETURN NEW; END IF;
 IF TG_OP='UPDATE' THEN
  IF NEW.enabled_revision IS NOT DISTINCT FROM OLD.enabled_revision THEN RETURN NEW; END IF;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM fs.isolated_policy_activation a
    JOIN fs.isolated_quota_policy_draft q USING(tenant_id,revision)
    JOIN fs.isolated_quota_policy_review r USING(tenant_id,revision)
    WHERE a.tenant_id=NEW.tenant_id AND a.revision=NEW.enabled_revision
      AND a.quota_limit=q.quota_limit AND a.evidence_ttl_seconds=q.evidence_ttl_seconds
      AND a.actor<>q.author AND a.actor<>r.reviewer AND a.activated_at>=r.reviewed_at)
 THEN RAISE EXCEPTION 'reviewed quota enablement required' USING ERRCODE='PQU01'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER reviewed_quota_enablement_guard BEFORE INSERT OR UPDATE ON fs.isolated_quota_fence
 FOR EACH ROW EXECUTE FUNCTION fs.guard_reviewed_quota_enablement();

-- Immutable snapshot facts; dry-run estimates are metadata only, never provider proof.
CREATE TABLE fs.isolated_quota_finding (
 finding_id uuid PRIMARY KEY, tenant_id uuid NOT NULL, policy_revision bigint,
 integrated boolean NOT NULL, captured_at timestamptz NOT NULL,
 requester varchar(128) NOT NULL CHECK(btrim(requester)<>''),
 missing_reservations bigint NOT NULL CHECK(missing_reservations>=0),
 mismatched_reservations bigint NOT NULL CHECK(mismatched_reservations>=0),
 untracked_versions bigint NOT NULL CHECK(untracked_versions>=0),
 uncertain_terminal bigint NOT NULL CHECK(uncertain_terminal>=0),
 candidate_reserved_bytes numeric NOT NULL CHECK(candidate_reserved_bytes>=0),
 candidate_charged_bytes numeric NOT NULL CHECK(candidate_charged_bytes>=0),
 candidate_released_bytes numeric NOT NULL CHECK(candidate_released_bytes>=0),
 unresolved_sessions bigint NOT NULL CHECK(unresolved_sessions>=0),
 UNIQUE(tenant_id,finding_id),
 FOREIGN KEY(tenant_id,policy_revision) REFERENCES fs.isolated_policy_activation(tenant_id,revision)
);
CREATE TABLE fs.isolated_quota_finding_review (
 tenant_id uuid NOT NULL, finding_id uuid NOT NULL, reviewer varchar(128) NOT NULL CHECK(btrim(reviewer)<>''),
 reviewed_at timestamptz NOT NULL, disposition text NOT NULL CHECK(disposition IN ('ACKNOWLEDGED','NEEDS_INVESTIGATION')),
 PRIMARY KEY(tenant_id,finding_id), FOREIGN KEY(tenant_id,finding_id) REFERENCES fs.isolated_quota_finding(tenant_id,finding_id)
);
CREATE TRIGGER quota_finding_immutable BEFORE UPDATE OR DELETE ON fs.isolated_quota_finding
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER quota_finding_review_immutable BEFORE UPDATE OR DELETE ON fs.isolated_quota_finding_review
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE FUNCTION fs.guard_quota_finding_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM fs.isolated_quota_finding f WHERE f.tenant_id=NEW.tenant_id AND f.finding_id=NEW.finding_id
    AND f.requester<>NEW.reviewer AND f.captured_at<=NEW.reviewed_at)
 THEN RAISE EXCEPTION 'independent finding review required' USING ERRCODE='PQU01'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER quota_finding_review_guard BEFORE INSERT ON fs.isolated_quota_finding_review
 FOR EACH ROW EXECUTE FUNCTION fs.guard_quota_finding_review();

-- No new grants to PUBLIC or production roles. All functions use invoker rights.
REVOKE ALL ON fs.isolated_quota_policy_draft,fs.isolated_quota_policy_review,
 fs.isolated_quota_finding,fs.isolated_quota_finding_review FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION fs.touch_quota_fence(uuid),fs.guard_quota_policy_draft(),fs.guard_quota_policy_review(),
 fs.guard_reviewed_quota_activation(),fs.guard_reviewed_quota_enablement(),fs.guard_quota_finding_review() FROM PUBLIC;
