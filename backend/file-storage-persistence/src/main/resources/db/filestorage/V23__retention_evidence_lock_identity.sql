-- FS-310A14: purge preparation uses a shared lock on retention evidence.
-- Permit only lock-only identity access, not mutation of evidence or policy.
CREATE TRIGGER lock_retention_evidence_identity BEFORE UPDATE OF file_id ON fs.file_retention_evidence
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('file_id');
