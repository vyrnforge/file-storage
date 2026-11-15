-- Deleted discovery is explicit; ordinary catalog/download grants confer no recovery access.
ALTER TABLE fs.file_grant DROP CONSTRAINT file_grant_action_check;
ALTER TABLE fs.file_grant ADD CONSTRAINT file_grant_action_check
    CHECK (action IN ('INITIATE','READ_METADATA','READ_SESSION','UPLOAD_TRANSFER','DOWNLOAD_TRANSFER','LIST_METADATA','DELETE_FILE','RESTORE_FILE','LIST_DELETED_METADATA'));
CREATE INDEX file_deleted_catalog_order ON fs.file(tenant_id,file_id COLLATE "C") WHERE state='SOFT_DELETED';
CREATE INDEX file_grant_deleted_catalog_scope ON fs.file_grant(tenant_id,actor,consumer,purpose)
    WHERE action='LIST_DELETED_METADATA' AND NOT revoked;

-- No default clear hold, retention period, policy writer or purge permission.
-- Evidence is optional and bound to one deletion generation. Absence/staleness blocks assessment.
CREATE TABLE fs.file_retention_evidence (
    tenant_id uuid NOT NULL,
    file_id varchar(512) NOT NULL,
    lifecycle_revision bigint NOT NULL CHECK (lifecycle_revision>0 AND lifecycle_revision%2=1),
    policy_revision bigint NOT NULL CHECK (policy_revision>0),
    hold_state text NOT NULL CHECK (hold_state IN ('UNKNOWN','HELD','CLEAR')),
    retain_until timestamptz,
    observed_at timestamptz NOT NULL,
    valid_until timestamptz NOT NULL CHECK (valid_until>observed_at),
    PRIMARY KEY (tenant_id,file_id,lifecycle_revision),
    FOREIGN KEY (tenant_id,file_id) REFERENCES fs.file(tenant_id,file_id)
);
