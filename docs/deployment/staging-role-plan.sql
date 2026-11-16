-- A16 review-only role plan derived from V4 isolated profile matrix.
-- Run only in a dedicated fs_staging database after explicit owner approval.
-- Requires authorized migration/schema owner with CREATEROLE; no LOGIN/passwords.
-- Default ROLLBACK is intentional. Review PUBLIC/default ACLs separately.
BEGIN;
DO $a16$
BEGIN
 IF current_database() <> 'fs_staging' OR NOT EXISTS (
   SELECT 1 FROM fs.flyway_schema_history WHERE version='23' AND success
 ) THEN RAISE EXCEPTION 'wrong staging database or migration state'; END IF;
END
$a16$;

-- METADATA / METADATA_API
CREATE ROLE fs_staging_metadata_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_metadata_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_metadata_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_metadata_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_metadata_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_metadata_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_metadata_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_metadata_profile;
GRANT SELECT ON fs.backend TO fs_staging_metadata_profile;
GRANT SELECT ON fs.backend_revision TO fs_staging_metadata_profile;
GRANT SELECT ON fs.storage_location TO fs_staging_metadata_profile;
GRANT SELECT ON fs.active_policy TO fs_staging_metadata_profile;
GRANT SELECT ON fs.policy_rule TO fs_staging_metadata_profile;
GRANT SELECT ON fs.policy_revision TO fs_staging_metadata_profile;
GRANT SELECT ON fs.file TO fs_staging_metadata_profile;
GRANT SELECT ON fs.file_version TO fs_staging_metadata_profile;
GRANT SELECT ON fs.upload_session TO fs_staging_metadata_profile;
GRANT SELECT ON fs.initiation_request TO fs_staging_metadata_profile;
GRANT SELECT ON fs.file_grant TO fs_staging_metadata_profile;
GRANT SELECT ON fs.upload_scan_evidence TO fs_staging_metadata_profile;
GRANT SELECT ON fs.upload_transfer_handle TO fs_staging_metadata_profile;
GRANT SELECT ON fs.isolated_quota_fence TO fs_staging_metadata_profile;
GRANT SELECT ON fs.isolated_quota_reservation TO fs_staging_metadata_profile;
GRANT SELECT ON fs.isolated_policy_activation TO fs_staging_metadata_profile;
GRANT UPDATE(endpoint) ON fs.backend TO fs_staging_metadata_profile;
GRANT INSERT ON fs.file TO fs_staging_metadata_profile;
GRANT INSERT ON fs.file_version TO fs_staging_metadata_profile;
GRANT INSERT ON fs.upload_session TO fs_staging_metadata_profile;
GRANT INSERT ON fs.initiation_request TO fs_staging_metadata_profile;
GRANT INSERT ON fs.isolated_quota_reservation TO fs_staging_metadata_profile;
GRANT INSERT(tenant_id) ON fs.isolated_quota_fence TO fs_staging_metadata_profile;
GRANT UPDATE(epoch) ON fs.isolated_quota_fence TO fs_staging_metadata_profile;
GRANT EXECUTE ON FUNCTION fs.touch_quota_fence(uuid) TO fs_staging_metadata_profile;
GRANT SELECT ON fs_kernel.platform_idempotency TO fs_staging_metadata_profile;
GRANT INSERT ON fs_kernel.platform_idempotency TO fs_staging_metadata_profile;
GRANT UPDATE ON fs_kernel.platform_idempotency TO fs_staging_metadata_profile;
GRANT DELETE ON fs_kernel.platform_idempotency TO fs_staging_metadata_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_metadata_profile;

-- LOGICAL / LOGICAL
CREATE ROLE fs_staging_logical_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_logical_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_logical_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_logical_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_logical_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_logical_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_logical_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_logical_profile;
GRANT SELECT ON fs.file TO fs_staging_logical_profile;
GRANT SELECT ON fs.file_version TO fs_staging_logical_profile;
GRANT SELECT ON fs.upload_session TO fs_staging_logical_profile;
GRANT SELECT ON fs.initiation_request TO fs_staging_logical_profile;
GRANT SELECT ON fs.upload_scan_evidence TO fs_staging_logical_profile;
GRANT SELECT ON fs.file_grant TO fs_staging_logical_profile;
GRANT UPDATE(state,lifecycle_revision,deleted_at) ON fs.file TO fs_staging_logical_profile;
GRANT UPDATE(version_id) ON fs.file_version TO fs_staging_logical_profile;
GRANT UPDATE(session_id) ON fs.upload_session TO fs_staging_logical_profile;
GRANT UPDATE(session_id) ON fs.initiation_request TO fs_staging_logical_profile;
GRANT UPDATE(grant_id) ON fs.file_grant TO fs_staging_logical_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_logical_profile;

-- PURGE_PREPARATION / PURGE_PREPARATION
CREATE ROLE fs_staging_purge_preparation_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_purge_preparation_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_purge_preparation_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_purge_preparation_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_purge_preparation_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.file TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.file_version TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.upload_session TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.initiation_request TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.upload_scan_evidence TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.file_grant TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.upload_transfer_handle TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.upload_integrity_verification TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.storage_operation TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.download_transfer_handle TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.file_retention_evidence TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.governed_legal_hold TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.isolated_policy_activation TO fs_staging_purge_preparation_profile;
GRANT SELECT ON fs.purge_preparation TO fs_staging_purge_preparation_profile;
GRANT UPDATE(file_id) ON fs.file TO fs_staging_purge_preparation_profile;
GRANT UPDATE(version_id) ON fs.file_version TO fs_staging_purge_preparation_profile;
GRANT UPDATE(operation_id) ON fs.storage_operation TO fs_staging_purge_preparation_profile;
GRANT UPDATE(grant_id) ON fs.file_grant TO fs_staging_purge_preparation_profile;
GRANT UPDATE(file_id) ON fs.file_retention_evidence TO fs_staging_purge_preparation_profile;
GRANT INSERT ON fs.purge_preparation TO fs_staging_purge_preparation_profile;
GRANT UPDATE(session_id) ON fs.upload_session TO fs_staging_purge_preparation_profile;
GRANT UPDATE(session_id) ON fs.initiation_request TO fs_staging_purge_preparation_profile;
GRANT UPDATE(session_id) ON fs.upload_transfer_handle TO fs_staging_purge_preparation_profile;
GRANT UPDATE(session_id) ON fs.upload_integrity_verification TO fs_staging_purge_preparation_profile;
GRANT UPDATE(session_id) ON fs.upload_scan_evidence TO fs_staging_purge_preparation_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_purge_preparation_profile;

-- PLACEMENT / PLACEMENT
CREATE ROLE fs_staging_placement_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_placement_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_placement_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_placement_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_placement_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_placement_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_placement_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_placement_profile;
GRANT SELECT ON fs.backend TO fs_staging_placement_profile;
GRANT SELECT ON fs.backend_revision TO fs_staging_placement_profile;
GRANT SELECT ON fs.storage_location TO fs_staging_placement_profile;
GRANT SELECT ON fs.active_policy TO fs_staging_placement_profile;
GRANT SELECT ON fs.policy_revision TO fs_staging_placement_profile;
GRANT SELECT ON fs.policy_rule TO fs_staging_placement_profile;
GRANT INSERT ON fs.policy_revision TO fs_staging_placement_profile;
GRANT INSERT ON fs.policy_rule TO fs_staging_placement_profile;
GRANT INSERT ON fs.active_policy TO fs_staging_placement_profile;
GRANT UPDATE(endpoint) ON fs.backend TO fs_staging_placement_profile;
GRANT UPDATE(sealed) ON fs.policy_revision TO fs_staging_placement_profile;
GRANT UPDATE(policy_revision) ON fs.active_policy TO fs_staging_placement_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_placement_profile;

-- BACKEND / BACKEND_ONLY
CREATE ROLE fs_staging_backend_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_backend_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_backend_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_backend_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_backend_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_backend_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_backend_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_backend_profile;
GRANT SELECT ON fs.backend TO fs_staging_backend_profile;
GRANT SELECT ON fs.backend_revision TO fs_staging_backend_profile;
GRANT SELECT ON fs.storage_location TO fs_staging_backend_profile;
GRANT SELECT ON fs.active_policy TO fs_staging_backend_profile;
GRANT SELECT ON fs.policy_revision TO fs_staging_backend_profile;
GRANT SELECT ON fs.policy_rule TO fs_staging_backend_profile;
GRANT SELECT ON fs.file_version TO fs_staging_backend_profile;
GRANT SELECT ON fs.upload_session TO fs_staging_backend_profile;
GRANT SELECT ON fs.storage_operation TO fs_staging_backend_profile;
GRANT INSERT ON fs.backend TO fs_staging_backend_profile;
GRANT INSERT ON fs.backend_revision TO fs_staging_backend_profile;
GRANT INSERT ON fs.storage_location TO fs_staging_backend_profile;
GRANT UPDATE(endpoint) ON fs.backend TO fs_staging_backend_profile;
GRANT UPDATE ON fs.storage_location TO fs_staging_backend_profile;
GRANT UPDATE(status,validated_revision) ON fs.backend_revision TO fs_staging_backend_profile;
GRANT EXECUTE ON FUNCTION fs.backend_retirement_blocked(uuid) TO fs_staging_backend_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_backend_profile;

-- GRANT_READ / GRANT_READ
CREATE ROLE fs_staging_grant_read_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_grant_read_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_grant_read_profile;
GRANT SELECT ON fs.file_grant TO fs_staging_grant_read_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_grant_read_profile;

-- READINESS / READINESS
CREATE ROLE fs_staging_readiness_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_readiness_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_readiness_profile;
GRANT SELECT ON fs.flyway_schema_history TO fs_staging_readiness_profile;
GRANT SELECT ON fs_kernel.flyway_schema_history TO fs_staging_readiness_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_readiness_profile;

-- TRANSFER / TRANSFER
CREATE ROLE fs_staging_transfer_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_transfer_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_transfer_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_transfer_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_transfer_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_transfer_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_transfer_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_transfer_profile;
GRANT SELECT ON fs.file TO fs_staging_transfer_profile;
GRANT SELECT ON fs.file_version TO fs_staging_transfer_profile;
GRANT SELECT ON fs.upload_session TO fs_staging_transfer_profile;
GRANT SELECT ON fs.initiation_request TO fs_staging_transfer_profile;
GRANT SELECT ON fs.backend TO fs_staging_transfer_profile;
GRANT SELECT ON fs.backend_revision TO fs_staging_transfer_profile;
GRANT SELECT ON fs.storage_location TO fs_staging_transfer_profile;
GRANT SELECT ON fs.file_grant TO fs_staging_transfer_profile;
GRANT SELECT ON fs.upload_transfer_handle TO fs_staging_transfer_profile;
GRANT SELECT ON fs.upload_integrity_verification TO fs_staging_transfer_profile;
GRANT SELECT ON fs.upload_scan_evidence TO fs_staging_transfer_profile;
GRANT SELECT ON fs.storage_operation TO fs_staging_transfer_profile;
GRANT SELECT ON fs.download_transfer_handle TO fs_staging_transfer_profile;
GRANT SELECT ON fs.download_reader TO fs_staging_transfer_profile;
GRANT UPDATE(file_id) ON fs.file TO fs_staging_transfer_profile;
GRANT UPDATE(version_id) ON fs.file_version TO fs_staging_transfer_profile;
GRANT UPDATE(session_id) ON fs.upload_session TO fs_staging_transfer_profile;
GRANT UPDATE(session_id) ON fs.initiation_request TO fs_staging_transfer_profile;
GRANT UPDATE(backend_id) ON fs.backend_revision TO fs_staging_transfer_profile;
GRANT UPDATE(grant_id) ON fs.file_grant TO fs_staging_transfer_profile;
GRANT UPDATE(session_id) ON fs.upload_scan_evidence TO fs_staging_transfer_profile;
GRANT UPDATE(operation_id) ON fs.storage_operation TO fs_staging_transfer_profile;
GRANT UPDATE(claimed_at) ON fs.upload_transfer_handle TO fs_staging_transfer_profile;
GRANT UPDATE(claimed_at) ON fs.download_transfer_handle TO fs_staging_transfer_profile;
GRANT UPDATE(session_id,verified_at,observed_size,observed_sha256) ON fs.upload_integrity_verification TO fs_staging_transfer_profile;
GRANT UPDATE(drained_at) ON fs.download_reader TO fs_staging_transfer_profile;
GRANT INSERT ON fs.upload_transfer_handle TO fs_staging_transfer_profile;
GRANT INSERT ON fs.download_transfer_handle TO fs_staging_transfer_profile;
GRANT INSERT ON fs.download_reader TO fs_staging_transfer_profile;
GRANT INSERT ON fs.upload_integrity_verification TO fs_staging_transfer_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_transfer_profile;

-- WORKER / COMPLETION_WORKER
CREATE ROLE fs_staging_worker_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_worker_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_worker_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_worker_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_worker_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_worker_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_worker_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_worker_profile;
GRANT SELECT ON fs.file TO fs_staging_worker_profile;
GRANT SELECT ON fs.file_version TO fs_staging_worker_profile;
GRANT SELECT ON fs.upload_session TO fs_staging_worker_profile;
GRANT SELECT ON fs.initiation_request TO fs_staging_worker_profile;
GRANT SELECT ON fs.backend TO fs_staging_worker_profile;
GRANT SELECT ON fs.backend_revision TO fs_staging_worker_profile;
GRANT SELECT ON fs.storage_location TO fs_staging_worker_profile;
GRANT SELECT ON fs.file_grant TO fs_staging_worker_profile;
GRANT SELECT ON fs.upload_transfer_handle TO fs_staging_worker_profile;
GRANT SELECT ON fs.upload_integrity_verification TO fs_staging_worker_profile;
GRANT SELECT ON fs.upload_scan_evidence TO fs_staging_worker_profile;
GRANT SELECT ON fs.upload_terminal_recovery TO fs_staging_worker_profile;
GRANT SELECT ON fs.storage_operation TO fs_staging_worker_profile;
GRANT SELECT ON fs.isolated_quota_reservation TO fs_staging_worker_profile;
GRANT SELECT ON fs.isolated_quota_fence TO fs_staging_worker_profile;
GRANT SELECT ON fs.isolated_policy_activation TO fs_staging_worker_profile;
GRANT UPDATE(file_id) ON fs.file TO fs_staging_worker_profile;
GRANT UPDATE(backend_id) ON fs.backend_revision TO fs_staging_worker_profile;
GRANT UPDATE(grant_id) ON fs.file_grant TO fs_staging_worker_profile;
GRANT UPDATE(endpoint) ON fs.backend TO fs_staging_worker_profile;
GRANT UPDATE(state) ON fs.file_version TO fs_staging_worker_profile;
GRANT UPDATE(state) ON fs.upload_session TO fs_staging_worker_profile;
GRANT INSERT ON fs.storage_operation TO fs_staging_worker_profile;
GRANT INSERT ON fs.upload_scan_evidence TO fs_staging_worker_profile;
GRANT INSERT ON fs.upload_terminal_recovery TO fs_staging_worker_profile;
GRANT UPDATE(status,last_error_code,finished_at,available_at,claim_token,locked_until,attempts) ON fs.storage_operation TO fs_staging_worker_profile;
GRANT INSERT ON fs.upload_integrity_verification TO fs_staging_worker_profile;
GRANT UPDATE(verified_at,observed_size,observed_sha256) ON fs.upload_integrity_verification TO fs_staging_worker_profile;
GRANT UPDATE(session_id) ON fs.initiation_request TO fs_staging_worker_profile;
GRANT UPDATE(session_id) ON fs.upload_integrity_verification TO fs_staging_worker_profile;
GRANT UPDATE(session_id) ON fs.upload_transfer_handle TO fs_staging_worker_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_worker_profile;

-- GOVERNANCE / GOVERNANCE
CREATE ROLE fs_staging_governance_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_governance_profile;
GRANT USAGE ON SCHEMA fs TO fs_staging_governance_profile;
GRANT SELECT ON fs_kernel.platform_audit_event TO fs_staging_governance_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_governance_profile;
GRANT INSERT ON fs_kernel.platform_audit_event TO fs_staging_governance_profile;
GRANT INSERT ON fs_kernel.platform_event_outbox TO fs_staging_governance_profile;
GRANT UPDATE(event_id) ON fs_kernel.platform_event_outbox TO fs_staging_governance_profile;
GRANT SELECT ON fs.file TO fs_staging_governance_profile;
GRANT SELECT ON fs.retention_policy_draft TO fs_staging_governance_profile;
GRANT SELECT ON fs.retention_policy_review TO fs_staging_governance_profile;
GRANT SELECT ON fs.governed_legal_hold TO fs_staging_governance_profile;
GRANT SELECT ON fs.download_transfer_handle TO fs_staging_governance_profile;
GRANT SELECT ON fs.download_reader TO fs_staging_governance_profile;
GRANT SELECT ON fs.reader_reconciliation TO fs_staging_governance_profile;
GRANT SELECT ON fs.reader_reconciliation_review TO fs_staging_governance_profile;
GRANT UPDATE(file_id) ON fs.file TO fs_staging_governance_profile;
GRANT INSERT ON fs.retention_policy_draft TO fs_staging_governance_profile;
GRANT INSERT ON fs.retention_policy_review TO fs_staging_governance_profile;
GRANT INSERT ON fs.governed_legal_hold TO fs_staging_governance_profile;
GRANT INSERT ON fs.reader_reconciliation TO fs_staging_governance_profile;
GRANT INSERT ON fs.reader_reconciliation_review TO fs_staging_governance_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_governance_profile;

-- DISPATCHER / DISPATCHER
CREATE ROLE fs_staging_dispatcher_profile NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT USAGE ON SCHEMA fs_kernel TO fs_staging_dispatcher_profile;
GRANT SELECT ON fs_kernel.platform_event_outbox TO fs_staging_dispatcher_profile;
GRANT UPDATE(status,last_error_code,published_at,available_at,locked_until,claim_token,attempts) ON fs_kernel.platform_event_outbox TO fs_staging_dispatcher_profile;
GRANT CONNECT ON DATABASE fs_staging TO fs_staging_dispatcher_profile;

-- Login creation, password issuance, group membership, PUBLIC/default ACL quarantine,
-- existing role drift reconciliation and credential rotation require separate review.
ROLLBACK;
