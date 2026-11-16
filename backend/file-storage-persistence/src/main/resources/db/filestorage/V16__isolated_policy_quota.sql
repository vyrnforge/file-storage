-- Explicit isolated policy activation history, never a production policy registry.
CREATE TABLE fs.isolated_policy_activation (
 tenant_id uuid NOT NULL, revision bigint NOT NULL, quota_limit bigint NOT NULL CHECK(quota_limit>0),
 evidence_ttl_seconds bigint NOT NULL CHECK(evidence_ttl_seconds BETWEEN 1 AND 86400),
 activated_at timestamptz NOT NULL, actor varchar(128) NOT NULL CHECK(btrim(actor)<>''),
 PRIMARY KEY(tenant_id,revision),
 FOREIGN KEY(tenant_id,revision) REFERENCES fs.retention_policy_review(tenant_id,revision)
);
CREATE TRIGGER isolated_activation_immutable BEFORE UPDATE OR DELETE ON fs.isolated_policy_activation
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();

-- Immutable evidence history. Projection into V12 is intentionally limited to isolated tenants.
CREATE TABLE fs.isolated_retention_observation (
 observation_id uuid PRIMARY KEY, tenant_id uuid NOT NULL, file_id varchar(512) NOT NULL,
 lifecycle_revision bigint NOT NULL CHECK(lifecycle_revision>0 AND lifecycle_revision%2=1),
 policy_revision bigint NOT NULL, retain_until timestamptz NOT NULL,
 hold_state text NOT NULL CHECK(hold_state IN ('UNKNOWN','HELD','CLEAR')),
 observed_at timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until>observed_at),
 actor varchar(128) NOT NULL,
 FOREIGN KEY(tenant_id,file_id) REFERENCES fs.file(tenant_id,file_id),
 FOREIGN KEY(tenant_id,policy_revision) REFERENCES fs.isolated_policy_activation(tenant_id,revision),
 UNIQUE(tenant_id,file_id,lifecycle_revision,observed_at)
);
CREATE TRIGGER isolated_observation_immutable BEFORE UPDATE OR DELETE ON fs.isolated_retention_observation
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();

-- One exact reservation per durable upload session. No release of charged bytes.
CREATE TABLE fs.isolated_quota_reservation (
 session_id uuid PRIMARY KEY, tenant_id uuid NOT NULL, file_id varchar(512) NOT NULL,
 version_id uuid NOT NULL, location_id uuid NOT NULL, consumer varchar(128) NOT NULL,
 policy_revision bigint NOT NULL, bytes bigint NOT NULL CHECK(bytes>=0),
 status text NOT NULL CHECK(status IN ('RESERVED','CHARGED','RELEASED')),
 created_at timestamptz NOT NULL, finished_at timestamptz,
 CHECK((status='RESERVED')=(finished_at IS NULL)),
 CHECK(finished_at IS NULL OR finished_at>=created_at),
 FOREIGN KEY(tenant_id,session_id,consumer) REFERENCES fs.upload_session(tenant_id,session_id,consumer),
 FOREIGN KEY(tenant_id,file_id,version_id,location_id) REFERENCES fs.file_version(tenant_id,file_id,version_id,location_id),
 FOREIGN KEY(tenant_id,policy_revision) REFERENCES fs.isolated_policy_activation(tenant_id,revision)
);
CREATE INDEX isolated_quota_tenant_status ON fs.isolated_quota_reservation(tenant_id,status);
CREATE FUNCTION fs.guard_isolated_quota() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'quota history retained' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status='RESERVED' AND EXISTS (
   SELECT 1 FROM fs.upload_session s JOIN fs.initiation_request r ON r.session_id=s.session_id AND r.tenant_id=s.tenant_id
   WHERE s.session_id=NEW.session_id AND s.tenant_id=NEW.tenant_id AND s.file_id=NEW.file_id
   AND s.version_id=NEW.version_id AND s.location_id=NEW.location_id AND s.consumer=NEW.consumer
   AND r.expected_size=NEW.bytes AND s.state='INITIATED'
  ) THEN RETURN NEW; END IF;
 ELSIF OLD.status='RESERVED' AND NEW.status IN ('CHARGED','RELEASED')
  AND ROW(NEW.session_id,NEW.tenant_id,NEW.file_id,NEW.version_id,NEW.location_id,NEW.consumer,NEW.policy_revision,NEW.bytes,NEW.created_at)
      IS NOT DISTINCT FROM ROW(OLD.session_id,OLD.tenant_id,OLD.file_id,OLD.version_id,OLD.location_id,OLD.consumer,OLD.policy_revision,OLD.bytes,OLD.created_at)
 THEN
  IF NEW.status='CHARGED' AND EXISTS (
   SELECT 1 FROM fs.upload_session s JOIN fs.file_version v ON v.version_id=s.version_id
   JOIN fs.upload_scan_evidence e ON e.session_id=s.session_id
   WHERE s.session_id=NEW.session_id AND s.state='COMPLETED' AND v.state='AVAILABLE' AND e.verdict='CLEAN' AND e.observed_size=NEW.bytes
  ) THEN RETURN NEW;
  ELSIF NEW.status='RELEASED' AND EXISTS (
   SELECT 1 FROM fs.upload_session s WHERE s.session_id=NEW.session_id AND s.state IN ('FAILED','EXPIRED')
   AND NOT EXISTS (SELECT 1 FROM fs.upload_transfer_handle h WHERE h.session_id=s.session_id AND h.claimed_at IS NOT NULL)
  ) THEN RETURN NEW; END IF;
 END IF;
 RAISE EXCEPTION 'invalid quota transition' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER isolated_quota_guard BEFORE INSERT OR UPDATE OR DELETE ON fs.isolated_quota_reservation
 FOR EACH ROW EXECUTE FUNCTION fs.guard_isolated_quota();
