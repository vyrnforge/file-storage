-- FS-310A13: transfer gateway row locks require UPDATE on a column,
-- not permission to rewrite resource identities or operation keys.
CREATE TRIGGER lock_version_identity BEFORE UPDATE OF version_id ON fs.file_version
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('version_id');
CREATE TRIGGER lock_session_identity BEFORE UPDATE OF session_id ON fs.upload_session
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('session_id');
CREATE TRIGGER lock_operation_identity BEFORE UPDATE OF operation_id ON fs.storage_operation
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('operation_id');
-- Scan evidence already rejects every UPDATE (V7).
-- No role creation, production grants, provider operations or policy activation.
