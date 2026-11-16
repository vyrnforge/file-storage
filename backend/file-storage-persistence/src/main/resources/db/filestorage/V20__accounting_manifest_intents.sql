-- FS-310A10: audited manifest adapter receipts. V19 raw isolated commands remain
-- explicit rehearsal capabilities; do not grant them to audited application logins.
CREATE TABLE fs.accounting_manifest_intent (
 tenant_id uuid NOT NULL, manifest_id uuid NOT NULL, policy_revision bigint NOT NULL,
 command text NOT NULL CHECK(command IN ('CAPTURE','REVIEW')),
 actor varchar(128) NOT NULL, disposition text,
 audit_id uuid NOT NULL UNIQUE, event_id uuid NOT NULL UNIQUE, correlation_id uuid NOT NULL,
 PRIMARY KEY(manifest_id,command),
 FOREIGN KEY(tenant_id,manifest_id,policy_revision)
 REFERENCES fs.accounting_manifest(tenant_id,manifest_id,policy_revision),
 CHECK((command='CAPTURE' AND disposition IS NULL) OR
 (command='REVIEW' AND disposition IS NOT NULL AND disposition IN ('ACKNOWLEDGED','NEEDS_INVESTIGATION')))
);
CREATE TRIGGER manifest_intent_immutable BEFORE UPDATE OR DELETE ON fs.accounting_manifest_intent
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();

-- Kernel schema is migrated separately; resolve its tables at execution time.
-- Deferred check prevents a command receipt from committing without its exact intents.
CREATE FUNCTION fs.check_manifest_intents() RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER
 SET search_path=pg_catalog,fs,pg_temp AS $a10$
DECLARE kind text; payload jsonb;
BEGIN
 kind:=CASE WHEN NEW.command='CAPTURE' THEN 'file-storage.accounting.manifest-captured'
 ELSE 'file-storage.accounting.manifest-reviewed' END;
 payload:=jsonb_build_object('policyRevision',NEW.policy_revision,'mode','DRY_RUN_ONLY');
 IF NEW.command='REVIEW' THEN payload:=payload||jsonb_build_object('disposition',NEW.disposition); END IF;
 IF NOT EXISTS(SELECT 1 FROM fs_kernel.platform_audit_event a WHERE a.audit_id=NEW.audit_id
  AND a.tenant_id=NEW.tenant_id AND a.actor_type='USER' AND a.actor_id=NEW.actor
  AND a.action=kind AND a.resource_type='file-storage.accounting-manifest'
  AND a.resource_id=NEW.manifest_id::text AND a.resource_tenant_id=NEW.tenant_id
  AND a.correlation_id=NEW.correlation_id AND a.outcome='SUCCESS' AND a.source='file-storage'
  AND a.changes_json @> payload)
 OR NOT EXISTS(SELECT 1 FROM fs_kernel.platform_event_outbox e WHERE e.event_id=NEW.event_id
  AND e.tenant_id=NEW.tenant_id AND e.actor_type='USER' AND e.actor_id=NEW.actor
  AND e.event_type=kind AND e.event_version=1 AND e.producer='file-storage'
  AND e.subject_type='file-storage.accounting-manifest' AND e.subject_id=NEW.manifest_id::text
  AND e.correlation_id=NEW.correlation_id AND e.payload_json=payload AND e.status='PENDING')
 THEN RAISE EXCEPTION 'manifest intent evidence required' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END; $a10$;
CREATE CONSTRAINT TRIGGER manifest_intents_required AFTER INSERT ON fs.accounting_manifest_intent
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fs.check_manifest_intents();

CREATE FUNCTION fs.audited_accounting_manifest(t uuid,mid uuid,rev bigint,cmd text,decision text,
 aid uuid,eid uuid,cid uuid) RETURNS TABLE(actor_id text,created boolean)
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs,pg_temp SET lock_timeout='2s' AS $a10$
DECLARE principal fs.accounting_manifest_principal%ROWTYPE; prior boolean; recorded fs.accounting_manifest_intent%ROWTYPE;
BEGIN
 SELECT * INTO principal FROM fs.accounting_manifest_principal
 WHERE login_role=session_user AND tenant_id=t AND capability=cmd FOR SHARE;
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
REVOKE ALL ON fs.accounting_manifest_intent FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION fs.check_manifest_intents(),
 fs.audited_accounting_manifest(uuid,uuid,bigint,text,text,uuid,uuid,uuid) FROM PUBLIC;
