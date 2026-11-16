-- FS-310A11: preserve legacy mappings disabled; explicit owner provisioning.
ALTER TABLE fs.accounting_manifest_principal ADD COLUMN execution_mode text NOT NULL
 DEFAULT 'DISABLED' CHECK(execution_mode IN ('DISABLED','REHEARSAL','AUDITED'));

-- Existing mappings fail closed until the owner explicitly chooses a mode.
CREATE OR REPLACE FUNCTION fs.capture_accounting_manifest(t uuid,mid uuid,rev bigint) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs,pg_temp SET lock_timeout='2s' SET timezone='UTC' AS $
DECLARE principal fs.accounting_manifest_principal%ROWTYPE; existing fs.accounting_manifest%ROWTYPE;
 n integer; ttl bigint; captured timestamptz; source_epoch bigint;
BEGIN
 SELECT * INTO principal FROM fs.accounting_manifest_principal
 WHERE login_role=session_user AND tenant_id=t AND capability='CAPTURE' AND execution_mode IN ('REHEARSAL','AUDITED') FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501'; END IF;
 IF mid IS NULL OR rev IS NULL THEN RAISE EXCEPTION 'manifest identity required' USING ERRCODE='PQU01'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(mid::text,3109));
 PERFORM fs.lock_accounting_sources();
 SELECT * INTO existing FROM fs.accounting_manifest WHERE manifest_id=mid;
 IF FOUND THEN
  IF existing.tenant_id=t AND existing.policy_revision=rev AND existing.requester=principal.actor THEN RETURN mid; END IF;
  RAISE EXCEPTION 'manifest conflict' USING ERRCODE='PQU01';
 END IF;
 ttl:=fs.accounting_policy_ttl(t,rev);
 SELECT count(*) INTO n FROM (SELECT 1 FROM fs.upload_session WHERE tenant_id=t LIMIT 1001) s;
 IF n>1000 OR (SELECT count(*) FROM (SELECT 1 FROM fs.file_version WHERE tenant_id=t LIMIT 1001) v)>1000
 THEN RAISE EXCEPTION 'manifest exceeds rehearsal bound' USING ERRCODE='PQU01'; END IF;
 INSERT INTO fs.accounting_source_epoch(tenant_id) VALUES(t) ON CONFLICT(tenant_id) DO NOTHING;
 SELECT epoch INTO STRICT source_epoch FROM fs.accounting_source_epoch WHERE tenant_id=t;
 captured:=clock_timestamp();
 INSERT INTO fs.accounting_manifest VALUES(mid,t,rev,source_epoch,principal.actor,captured,
  captured+make_interval(secs=>ttl::double precision),n,fs.accounting_untracked(t));
 INSERT INTO fs.accounting_manifest_entry
 SELECT t,mid,rev,s.session_id,s.evidence FROM fs.accounting_source_entries(t) s;
 RETURN mid;
END; $;

CREATE OR REPLACE FUNCTION fs.review_accounting_manifest(t uuid,mid uuid,rev bigint,decision text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs,pg_temp SET lock_timeout='2s' SET timezone='UTC' AS $
DECLARE principal fs.accounting_manifest_principal%ROWTYPE; m fs.accounting_manifest%ROWTYPE;
 prior fs.accounting_manifest_review%ROWTYPE;
BEGIN
 SELECT * INTO principal FROM fs.accounting_manifest_principal
 WHERE login_role=session_user AND tenant_id=t AND capability='REVIEW' AND execution_mode IN ('REHEARSAL','AUDITED') FOR SHARE;
 IF NOT FOUND THEN RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501'; END IF;
 IF mid IS NULL OR rev IS NULL THEN RAISE EXCEPTION 'manifest identity required' USING ERRCODE='PQU01'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(mid::text,3109));
 PERFORM fs.lock_accounting_sources();
 SELECT * INTO m FROM fs.accounting_manifest WHERE tenant_id=t AND manifest_id=mid AND policy_revision=rev;
 IF NOT FOUND OR m.requester=principal.actor OR decision IS NULL OR decision NOT IN ('ACKNOWLEDGED','NEEDS_INVESTIGATION')
 THEN RAISE EXCEPTION 'invalid independent manifest review' USING ERRCODE='PQU01'; END IF;
 SELECT * INTO prior FROM fs.accounting_manifest_review WHERE manifest_id=mid;
 IF FOUND THEN
  IF prior.reviewer=principal.actor AND prior.disposition=decision THEN RETURN mid; END IF;
  RAISE EXCEPTION 'manifest review conflict' USING ERRCODE='PQU01';
 END IF;
 PERFORM fs.accounting_policy_ttl(t,rev);
 IF m.valid_until<=clock_timestamp() OR m.source_epoch IS DISTINCT FROM
 (SELECT epoch FROM fs.accounting_source_epoch WHERE tenant_id=t)
 OR m.untracked_versions IS DISTINCT FROM fs.accounting_untracked(t)
 OR EXISTS(
  (SELECT s.session_id,s.evidence FROM fs.accounting_source_entries(t) s
   EXCEPT SELECT e.session_id,e.evidence FROM fs.accounting_manifest_entry e WHERE e.manifest_id=mid)
  UNION ALL
  (SELECT e.session_id,e.evidence FROM fs.accounting_manifest_entry e WHERE e.manifest_id=mid
   EXCEPT SELECT s.session_id,s.evidence FROM fs.accounting_source_entries(t) s)
 ) THEN RAISE EXCEPTION 'stale accounting manifest' USING ERRCODE='PQU01'; END IF;
 INSERT INTO fs.accounting_manifest_review VALUES(t,mid,rev,principal.actor,clock_timestamp(),decision);
 RETURN mid;
END; $;

CREATE OR REPLACE FUNCTION fs.read_accounting_manifest(t uuid,mid uuid) RETURNS jsonb
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs,pg_temp SET timezone='UTC' AS $
DECLARE result jsonb;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM fs.accounting_manifest_principal
  WHERE login_role=session_user AND tenant_id=t AND execution_mode IN ('REHEARSAL','AUDITED')) THEN
  RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501';
 END IF;
 SELECT jsonb_build_object('manifest',to_jsonb(m),'entries',
  COALESCE((SELECT jsonb_agg(jsonb_build_object('sessionId',e.session_id,'evidence',e.evidence) ORDER BY e.session_id)
    FROM fs.accounting_manifest_entry e WHERE e.manifest_id=m.manifest_id),'[]'::jsonb),
  'review',(SELECT to_jsonb(r) FROM fs.accounting_manifest_review r WHERE r.manifest_id=m.manifest_id),
  'current',m.valid_until>clock_timestamp() AND m.source_epoch=(SELECT epoch FROM fs.accounting_source_epoch x WHERE x.tenant_id=t))
 INTO result FROM fs.accounting_manifest m WHERE m.tenant_id=t AND m.manifest_id=mid;
 IF result IS NULL THEN RAISE EXCEPTION 'manifest not found' USING ERRCODE='PQU01'; END IF;
 RETURN result;
END; $;

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
