-- FS-310A11: preserve legacy mappings as rehearsal-only; explicit owner provisioning.
ALTER TABLE fs.accounting_manifest_principal ADD COLUMN execution_mode text NOT NULL
 DEFAULT 'REHEARSAL' CHECK(execution_mode IN ('REHEARSAL','AUDITED'));
CREATE OR REPLACE FUNCTION fs.audited_accounting_manifest(t uuid,mid uuid,rev bigint,cmd text,decision text,
 aid uuid,eid uuid,cid uuid) RETURNS TABLE(actor_id text,created boolean)
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs,pg_temp SET lock_timeout='2s' AS $a10$
DECLARE principal fs.accounting_manifest_principal%ROWTYPE; prior boolean; recorded fs.accounting_manifest_intent%ROWTYPE;
BEGIN
 SELECT * INTO principal FROM fs.accounting_manifest_principal
 WHERE login_role=session_user AND tenant_id=t AND capability=cmd AND execution_mode='AUDITED' FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501'; END IF;
 IF mid IS NULL OR rev IS NULL OR aid IS NULL OR eid IS NULL OR cid IS NULL
 OR (cmd='CAPTURE' AND decision IS NOT NULL) THEN
 RAISE EXCEPTION 'invalid manifest command' USING ERRCODE='PQU01'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(mid::text,3109));
 IF cmd='CAPTURE' THEN
  SELECT EXISTS(SELECT 1 FROM fs.accounting_manifest WHERE manifest_id=mid) INTO prior;
  PERFORM fs.capture_accounting_manifest(t,mid,rev);
 ELSE
  SELECT EXISTS(SELECT 1 FROM fs.accounting_manifest_review WHERE manifest_id=mid) INTO prior;
  PERFORM fs.review_accounting_manifest(t,mid,rev,decision);
 END IF;
 SELECT * INTO recorded FROM fs.accounting_manifest_intent WHERE manifest_id=mid AND command=cmd;
 IF FOUND THEN
  IF recorded.tenant_id<>t OR recorded.policy_revision<>rev OR recorded.actor<>principal.actor
   OR recorded.disposition IS DISTINCT FROM decision THEN
   RAISE EXCEPTION 'manifest intent conflict' USING ERRCODE='PQU01'; END IF;
  RETURN QUERY SELECT principal.actor::text,false; RETURN;
 END IF;
 -- Never silently manufacture retrospective audit evidence for an existing raw command.
 IF prior THEN RAISE EXCEPTION 'legacy manifest command has no audited receipt' USING ERRCODE='PQU01'; END IF;
 INSERT INTO fs.accounting_manifest_intent VALUES(t,mid,rev,cmd,principal.actor,decision,aid,eid,cid);
 RETURN QUERY SELECT principal.actor::text,true;
END; $a10$;

-- An accidentally inherited raw EXECUTE grant cannot commit a new audited command.
CREATE FUNCTION fs.require_audited_manifest_receipt() RETURNS trigger LANGUAGE plpgsql
 SECURITY DEFINER SET search_path=pg_catalog,fs,pg_temp AS $a11$
DECLARE cmd text; who text; decision text;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM fs.accounting_manifest_principal p
   WHERE p.login_role=session_user AND p.tenant_id=NEW.tenant_id AND p.execution_mode='AUDITED')
 THEN RETURN NULL; END IF;
 IF TG_TABLE_NAME='accounting_manifest' THEN
  cmd:='CAPTURE'; who:=NEW.requester; decision:=NULL;
 ELSE
  cmd:='REVIEW'; who:=NEW.reviewer; decision:=NEW.disposition;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM fs.accounting_manifest_intent i WHERE i.tenant_id=NEW.tenant_id
   AND i.manifest_id=NEW.manifest_id AND i.policy_revision=NEW.policy_revision
   AND i.command=cmd AND i.actor=who AND i.disposition IS NOT DISTINCT FROM decision)
 THEN RAISE EXCEPTION 'audited manifest receipt required' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END; $a11$;
CREATE CONSTRAINT TRIGGER audited_manifest_capture_required AFTER INSERT ON fs.accounting_manifest
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fs.require_audited_manifest_receipt();
CREATE CONSTRAINT TRIGGER audited_manifest_review_required AFTER INSERT ON fs.accounting_manifest_review
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fs.require_audited_manifest_receipt();
REVOKE EXECUTE ON FUNCTION fs.require_audited_manifest_receipt() FROM PUBLIC;

-- A column UPDATE privilege for FOR SHARE is not permission to rewrite identity.
CREATE FUNCTION fs.guard_lock_identity() RETURNS trigger LANGUAGE plpgsql
 SET search_path=pg_catalog,fs,pg_temp AS $a11$
BEGIN
 IF (to_jsonb(NEW)->TG_ARGV[0]) IS DISTINCT FROM (to_jsonb(OLD)->TG_ARGV[0])
 THEN RAISE EXCEPTION 'immutable lock identity' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $a11$;
CREATE TRIGGER lock_file_identity BEFORE UPDATE OF file_id ON fs.file
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('file_id');
CREATE TRIGGER lock_initiation_request_identity BEFORE UPDATE OF session_id ON fs.initiation_request
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('session_id');
CREATE TRIGGER lock_upload_integrity_verification_identity BEFORE UPDATE OF session_id ON fs.upload_integrity_verification
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('session_id');
CREATE TRIGGER lock_upload_transfer_handle_identity BEFORE UPDATE OF session_id ON fs.upload_transfer_handle
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('session_id');
CREATE TRIGGER lock_backend_revision_identity BEFORE UPDATE OF backend_id ON fs.backend_revision
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('backend_id');
CREATE TRIGGER lock_file_grant_identity BEFORE UPDATE OF grant_id ON fs.file_grant
 FOR EACH ROW EXECUTE FUNCTION fs.guard_lock_identity('grant_id');
REVOKE EXECUTE ON FUNCTION fs.guard_lock_identity() FROM PUBLIC;

-- Existing settlement logic unchanged; worker loses direct settlement/fence write privileges.
ALTER FUNCTION fs.settle_integrated_quota() SECURITY DEFINER;
ALTER FUNCTION fs.settle_integrated_quota() SET search_path=pg_catalog,fs,pg_temp;
REVOKE EXECUTE ON FUNCTION fs.settle_integrated_quota() FROM PUBLIC;
