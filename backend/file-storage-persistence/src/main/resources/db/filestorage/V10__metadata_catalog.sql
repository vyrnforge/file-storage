-- Explicit discovery permission; never inferred from individual metadata or download access.
ALTER TABLE fs.file_grant DROP CONSTRAINT file_grant_action_check;
ALTER TABLE fs.file_grant ADD CONSTRAINT file_grant_action_check
    CHECK (action IN ('INITIATE','READ_METADATA','READ_SESSION','UPLOAD_TRANSFER','DOWNLOAD_TRANSFER','LIST_METADATA'));

CREATE INDEX file_catalog_order ON fs.file(tenant_id,file_id COLLATE "C") WHERE state='ACTIVE';
CREATE INDEX file_grant_catalog_scope ON fs.file_grant(tenant_id,actor,consumer,purpose)
    WHERE action='LIST_METADATA' AND NOT revoked;
