-- Reversible logical lifecycle; physical purge remains prohibited.
ALTER TABLE fs.file DROP CONSTRAINT file_state_check;
ALTER TABLE fs.file ADD COLUMN lifecycle_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE fs.file ADD COLUMN deleted_at timestamptz;
ALTER TABLE fs.file ADD CONSTRAINT file_state_check CHECK (
    lifecycle_revision>=0 AND (
        (state='ACTIVE' AND deleted_at IS NULL AND lifecycle_revision%2=0)
        OR (state='SOFT_DELETED' AND deleted_at IS NOT NULL AND lifecycle_revision%2=1)
    )
);
ALTER TABLE fs.file_grant DROP CONSTRAINT file_grant_action_check;
ALTER TABLE fs.file_grant ADD CONSTRAINT file_grant_action_check
    CHECK (action IN ('INITIATE','READ_METADATA','READ_SESSION','UPLOAD_TRANSFER','DOWNLOAD_TRANSFER','LIST_METADATA','DELETE_FILE','RESTORE_FILE'));

ALTER TABLE fs.download_transfer_handle ADD COLUMN lifecycle_revision bigint NOT NULL DEFAULT 0
    CHECK (lifecycle_revision>=0);
-- Preserve generation immutability independently of the existing transfer guard.
CREATE FUNCTION fs.guard_download_lifecycle_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.lifecycle_revision IS DISTINCT FROM OLD.lifecycle_revision THEN
        RAISE EXCEPTION 'immutable download lifecycle revision' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER download_lifecycle_revision_guard BEFORE UPDATE ON fs.download_transfer_handle
    FOR EACH ROW EXECUTE FUNCTION fs.guard_download_lifecycle_revision();
