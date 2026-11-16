-- Preparation is distinct from executable work. No default policy or purge permission.
ALTER TABLE fs.file_grant DROP CONSTRAINT file_grant_action_check;
ALTER TABLE fs.file_grant ADD CONSTRAINT file_grant_action_check
    CHECK (action IN ('INITIATE','READ_METADATA','READ_SESSION','UPLOAD_TRANSFER','DOWNLOAD_TRANSFER','LIST_METADATA',
                     'DELETE_FILE','RESTORE_FILE','LIST_DELETED_METADATA','PREPARE_PURGE'));

-- Fail migration rather than silently enable or discard any previously reserved purge work.
ALTER TABLE fs.storage_operation ADD CONSTRAINT storage_operation_purge_disabled CHECK (kind<>'PURGE_VERSION');

CREATE TABLE fs.purge_preparation (
    operation_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    file_id varchar(512) NOT NULL,
    version_id uuid NOT NULL,
    location_id uuid NOT NULL,
    backend_id uuid NOT NULL,
    config_revision bigint NOT NULL,
    object_key_sha256 varchar(64) NOT NULL CHECK (object_key_sha256 ~ '^[0-9a-f]{64}$'),
    content_sha256 varchar(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    content_size bigint NOT NULL CHECK (content_size>=0),
    lifecycle_revision bigint NOT NULL CHECK (lifecycle_revision>0 AND lifecycle_revision%2=1),
    policy_revision bigint NOT NULL CHECK (policy_revision>0),
    retain_until timestamptz NOT NULL,
    evidence_observed_at timestamptz NOT NULL,
    evidence_valid_until timestamptz NOT NULL,
    actor varchar(128) NOT NULL CHECK (btrim(actor)<>''),
    consumer varchar(128) NOT NULL CHECK (btrim(consumer)<>''),
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    disposition text NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (disposition='REVIEW_REQUIRED'),
    reader_safety text NOT NULL DEFAULT 'UNVERIFIED' CHECK (reader_safety='UNVERIFIED'),
    prior_download_claim boolean NOT NULL,
    CHECK (retain_until<=created_at AND evidence_observed_at<=created_at AND created_at<evidence_valid_until),
    UNIQUE (tenant_id,file_id,lifecycle_revision),
    FOREIGN KEY (tenant_id,file_id,version_id,location_id)
        REFERENCES fs.file_version(tenant_id,file_id,version_id,location_id),
    FOREIGN KEY (location_id,backend_id) REFERENCES fs.storage_location(location_id,backend_id),
    FOREIGN KEY (backend_id,config_revision) REFERENCES fs.backend_revision,
    FOREIGN KEY (tenant_id,file_id,lifecycle_revision)
        REFERENCES fs.file_retention_evidence(tenant_id,file_id,lifecycle_revision)
);
-- Evidence is a historical snapshot, not a mutable execution token.
CREATE FUNCTION fs.guard_purge_preparation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable purge preparation' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER purge_preparation_immutable BEFORE UPDATE OR DELETE ON fs.purge_preparation
    FOR EACH ROW EXECUTE FUNCTION fs.guard_purge_preparation();
