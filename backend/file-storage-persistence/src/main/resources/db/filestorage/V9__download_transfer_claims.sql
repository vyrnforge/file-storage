-- Independent explicit byte-download grant and short-lived single-use capabilities.
ALTER TABLE fs.file_grant DROP CONSTRAINT file_grant_action_check;
ALTER TABLE fs.file_grant ADD CONSTRAINT file_grant_action_check
    CHECK (action IN ('INITIATE','READ_METADATA','READ_SESSION','UPLOAD_TRANSFER','DOWNLOAD_TRANSFER'));

CREATE TABLE fs.download_transfer_handle (
    handle_hash bytea PRIMARY KEY CHECK (octet_length(handle_hash)=32),
    tenant_id uuid NOT NULL,
    actor varchar(128) NOT NULL CHECK (btrim(actor)<>''),
    consumer varchar(128) NOT NULL CHECK (btrim(consumer)<>''),
    file_id varchar(512) NOT NULL,
    version_id uuid NOT NULL,
    location_id uuid NOT NULL,
    backend_id uuid NOT NULL,
    config_revision bigint NOT NULL,
    object_key text NOT NULL CHECK (octet_length(object_key) BETWEEN 1 AND 1024),
    expected_size bigint NOT NULL CHECK (expected_size>=0),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz NOT NULL,
    claimed_at timestamptz,
    CHECK (claimed_at IS NULL OR claimed_at<expires_at),
    FOREIGN KEY (tenant_id,file_id,version_id,location_id)
        REFERENCES fs.file_version(tenant_id,file_id,version_id,location_id),
    FOREIGN KEY (location_id,backend_id) REFERENCES fs.storage_location(location_id,backend_id),
    FOREIGN KEY (backend_id,config_revision) REFERENCES fs.backend_revision
);
CREATE INDEX download_transfer_handle_expiry ON fs.download_transfer_handle(expires_at);
CREATE TRIGGER download_transfer_handle_guard BEFORE UPDATE ON fs.download_transfer_handle
    FOR EACH ROW EXECUTE FUNCTION fs.guard_upload_transfer_handle();
