-- Drafts/reviews are not active production policy.
ALTER TABLE fs.file_grant DROP CONSTRAINT file_grant_action_check;
ALTER TABLE fs.file_grant ADD CONSTRAINT file_grant_action_check CHECK (action IN
 ('INITIATE','READ_METADATA','READ_SESSION','UPLOAD_TRANSFER','DOWNLOAD_TRANSFER','LIST_METADATA',
  'DELETE_FILE','RESTORE_FILE','LIST_DELETED_METADATA','PREPARE_PURGE','SUPERSEDE_PURGE'));
CREATE TABLE fs.retention_policy_draft (
 tenant_id uuid NOT NULL, revision bigint NOT NULL CHECK(revision>0),
 retention_seconds bigint NOT NULL CHECK(retention_seconds>0),
 quota_mode text NOT NULL CHECK(quota_mode IN ('CHARGE_UNTIL_VERIFIED_PURGE','RELEASE_ON_LOGICAL_DELETE')),
 author varchar(128) NOT NULL, created_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,revision)
);
CREATE TABLE fs.retention_policy_review (
 tenant_id uuid NOT NULL, revision bigint NOT NULL, reviewer varchar(128) NOT NULL,
 reviewed_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,revision),
 FOREIGN KEY(tenant_id,revision) REFERENCES fs.retention_policy_draft
);
CREATE TABLE fs.governed_legal_hold (
 tenant_id uuid NOT NULL, file_id varchar(512) NOT NULL, hold_id uuid NOT NULL,
 authority_actor varchar(128) NOT NULL, evidence_sha256 varchar(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 asserted_at timestamptz NOT NULL, PRIMARY KEY(tenant_id,file_id,hold_id),
 FOREIGN KEY(tenant_id,file_id) REFERENCES fs.file
);
ALTER TABLE fs.purge_preparation DROP CONSTRAINT purge_preparation_tenant_id_file_id_lifecycle_revision_key;
ALTER TABLE fs.purge_preparation ADD COLUMN supersedes_operation_id uuid UNIQUE REFERENCES fs.purge_preparation(operation_id);
CREATE UNIQUE INDEX purge_preparation_root ON fs.purge_preparation(tenant_id,file_id,lifecycle_revision) WHERE supersedes_operation_id IS NULL;
CREATE FUNCTION fs.guard_preparation_successor() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.supersedes_operation_id IS NOT NULL AND NOT EXISTS (
 SELECT 1 FROM fs.purge_preparation p WHERE p.operation_id=NEW.supersedes_operation_id
 AND p.tenant_id=NEW.tenant_id AND p.file_id=NEW.file_id AND p.lifecycle_revision=NEW.lifecycle_revision
 AND p.version_id=NEW.version_id AND p.operation_id<>NEW.operation_id
 ) THEN RAISE EXCEPTION 'invalid preparation predecessor' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER purge_preparation_successor BEFORE INSERT ON fs.purge_preparation FOR EACH ROW EXECUTE FUNCTION fs.guard_preparation_successor();
CREATE TABLE fs.reader_reconciliation (
 request_id uuid PRIMARY KEY, handle_hash bytea NOT NULL UNIQUE REFERENCES fs.download_transfer_handle(handle_hash),
 evidence_sha256 varchar(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
 requester varchar(128) NOT NULL, requested_at timestamptz NOT NULL
);
CREATE TABLE fs.reader_reconciliation_review (
 request_id uuid PRIMARY KEY REFERENCES fs.reader_reconciliation(request_id),
 reviewer varchar(128) NOT NULL, reviewed_at timestamptz NOT NULL,
 verdict text NOT NULL CHECK(verdict='CONFIRMED_STOPPED')
);
CREATE FUNCTION fs.guard_governance_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'immutable governance evidence' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER retention_draft_immutable BEFORE UPDATE OR DELETE ON fs.retention_policy_draft FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER retention_review_immutable BEFORE UPDATE OR DELETE ON fs.retention_policy_review FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER legal_hold_immutable BEFORE UPDATE OR DELETE ON fs.governed_legal_hold FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER reader_reconciliation_immutable BEFORE UPDATE OR DELETE ON fs.reader_reconciliation FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER reader_review_immutable BEFORE UPDATE OR DELETE ON fs.reader_reconciliation_review FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE FUNCTION fs.guard_governance_review() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='retention_policy_review' THEN
  IF NOT EXISTS(SELECT 1 FROM fs.retention_policy_draft d WHERE d.tenant_id=NEW.tenant_id
   AND d.revision=NEW.revision AND d.author<>NEW.reviewer AND d.created_at<=NEW.reviewed_at)
  THEN RAISE EXCEPTION 'independent policy review required' USING ERRCODE='23514'; END IF;
 ELSE
  IF NOT EXISTS(SELECT 1 FROM fs.reader_reconciliation q WHERE q.request_id=NEW.request_id
   AND q.requester<>NEW.reviewer AND q.requested_at<=NEW.reviewed_at)
  THEN RAISE EXCEPTION 'independent reader review required' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER retention_review_guard BEFORE INSERT ON fs.retention_policy_review FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_review();
CREATE TRIGGER reader_review_guard BEFORE INSERT ON fs.reader_reconciliation_review FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_review();
