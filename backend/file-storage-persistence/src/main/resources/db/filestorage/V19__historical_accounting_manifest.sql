-- FS-310A9: dry-run only. No ledger writer, provider calls or production grants.
-- Only an explicitly provisioned isolated login can call the two command functions.
CREATE TABLE fs.accounting_manifest_principal (
 login_role name PRIMARY KEY, tenant_id uuid NOT NULL,
 actor varchar(128) NOT NULL CHECK (btrim(actor)<>''),
 capability text NOT NULL CHECK (capability IN ('CAPTURE','REVIEW'))
);
CREATE TABLE fs.accounting_manifest (
 manifest_id uuid PRIMARY KEY, tenant_id uuid NOT NULL, policy_revision bigint NOT NULL,
 requester varchar(128) NOT NULL, captured_at timestamptz NOT NULL, valid_until timestamptz NOT NULL,
 session_count integer NOT NULL CHECK (session_count BETWEEN 0 AND 1000),
 untracked_versions jsonb NOT NULL CHECK (jsonb_typeof(untracked_versions)='array'),
 CHECK (valid_until>captured_at),
 UNIQUE(tenant_id,manifest_id,policy_revision),
 FOREIGN KEY(tenant_id,policy_revision) REFERENCES fs.isolated_policy_activation(tenant_id,revision)
);
CREATE TABLE fs.accounting_manifest_entry (
 tenant_id uuid NOT NULL, manifest_id uuid NOT NULL, policy_revision bigint NOT NULL,
 session_id uuid NOT NULL, evidence jsonb NOT NULL CHECK(jsonb_typeof(evidence)='object'),
 PRIMARY KEY(manifest_id,session_id),
 FOREIGN KEY(tenant_id,manifest_id,policy_revision)
 REFERENCES fs.accounting_manifest(tenant_id,manifest_id,policy_revision)
);
CREATE TABLE fs.accounting_manifest_review (
 tenant_id uuid NOT NULL, manifest_id uuid PRIMARY KEY, policy_revision bigint NOT NULL,
 reviewer varchar(128) NOT NULL, reviewed_at timestamptz NOT NULL,
 disposition text NOT NULL CHECK(disposition IN ('ACKNOWLEDGED','NEEDS_INVESTIGATION')),
 FOREIGN KEY(tenant_id,manifest_id,policy_revision)
 REFERENCES fs.accounting_manifest(tenant_id,manifest_id,policy_revision)
);
CREATE TRIGGER manifest_immutable BEFORE UPDATE OR DELETE ON fs.accounting_manifest
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER manifest_entry_immutable BEFORE UPDATE OR DELETE ON fs.accounting_manifest_entry
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();
CREATE TRIGGER manifest_review_immutable BEFORE UPDATE OR DELETE ON fs.accounting_manifest_review
 FOR EACH ROW EXECUTE FUNCTION fs.guard_governance_history();

-- Explicit coarse rehearsal fence: blocks source writes, including legacy/non-integrated
-- writers that do not touch the V17 quota fence. READ COMMITTED avoids old snapshots.
-- Lock acquisition is bounded per lock; caller must also bound the entire transaction.
CREATE FUNCTION fs.lock_accounting_sources() RETURNS void LANGUAGE plpgsql
 SET lock_timeout='2s' SET search_path=pg_catalog,fs AS $$
BEGIN
 IF current_setting('transaction_isolation')<>'read committed' THEN
  RAISE EXCEPTION 'manifest requires read committed' USING ERRCODE='PQU01';
 END IF;
 LOCK TABLE fs.file,fs.file_version,fs.upload_session,fs.initiation_request,
  fs.upload_transfer_handle,fs.upload_integrity_verification,fs.isolated_quota_reservation,
  fs.isolated_policy_activation,fs.isolated_quota_policy_draft,fs.isolated_quota_policy_review,
  fs.isolated_quota_fence IN SHARE MODE;
END; $$;

-- Snapshot exact accounting inputs, never object keys, provider credentials, names or handles.
-- A completed session is only a candidate charge with matching durable integrity evidence.
CREATE FUNCTION fs.accounting_source_entries(t uuid)
 RETURNS TABLE(session_id uuid,evidence jsonb) LANGUAGE sql STABLE
 SET search_path=pg_catalog,fs AS $$
 SELECT s.session_id,jsonb_build_object(
  'fileId',s.file_id,'versionId',s.version_id,'locationId',s.location_id,
  'backendId',s.backend_id,'configRevision',s.config_revision,'placementRevision',s.policy_revision,
  'consumer',s.consumer,'purpose',s.purpose,'sessionState',s.state,
  'expiresAt',s.expires_at,'expiresNanoRemainder',s.expires_nano_remainder,
  'fileState',f.state,'versionState',v.state,'expectedBytes',r.expected_size,
  'claimed',h.claimed_at IS NOT NULL,'claimedAt',h.claimed_at,
  'verifiedAt',i.verified_at,'verifiedBytes',i.observed_size,'verifiedDigest',i.observed_sha256,
  'ledgerRevision',q.policy_revision,'ledgerBytes',q.bytes,'ledgerStatus',q.status,
  'ledgerFile',q.file_id,'ledgerVersion',q.version_id,'ledgerLocation',q.location_id,'ledgerConsumer',q.consumer,
  'candidate',CASE
    WHEN q.session_id IS NOT NULL THEN 'ALREADY_ACCOUNTED'
    WHEN r.expected_size IS NULL THEN 'UNRESOLVED'
    WHEN s.state='COMPLETED' AND i.verified_at IS NOT NULL AND i.observed_size=r.expected_size THEN 'CHARGED'
    WHEN s.state IN ('FAILED','EXPIRED') AND h.claimed_at IS NULL THEN 'RELEASED'
    WHEN s.state IN ('FAILED','EXPIRED','COMPLETED') THEN 'UNRESOLVED'
    ELSE 'RESERVED' END)
 FROM fs.upload_session s JOIN fs.file f ON f.tenant_id=s.tenant_id AND f.file_id=s.file_id
 JOIN fs.file_version v ON v.tenant_id=s.tenant_id AND v.version_id=s.version_id
 LEFT JOIN fs.initiation_request r ON r.tenant_id=s.tenant_id AND r.session_id=s.session_id
 LEFT JOIN fs.upload_transfer_handle h ON h.session_id=s.session_id
 LEFT JOIN fs.upload_integrity_verification i ON i.session_id=s.session_id
 LEFT JOIN fs.isolated_quota_reservation q ON q.tenant_id=s.tenant_id AND q.session_id=s.session_id
 WHERE s.tenant_id=t
 $$;
CREATE FUNCTION fs.accounting_untracked(t uuid) RETURNS jsonb LANGUAGE sql STABLE
 SET search_path=pg_catalog,fs AS $$
 SELECT COALESCE(jsonb_agg(jsonb_build_object('versionId',v.version_id,'fileId',v.file_id,
  'locationId',v.location_id,'state',v.state) ORDER BY v.version_id),'[]'::jsonb)
 FROM fs.file_version v WHERE v.tenant_id=t AND NOT EXISTS
 (SELECT 1 FROM fs.upload_session s WHERE s.tenant_id=t AND s.version_id=v.version_id)
 $$;
CREATE FUNCTION fs.accounting_policy_ttl(t uuid,rev bigint) RETURNS bigint LANGUAGE plpgsql STABLE
 SET search_path=pg_catalog,fs AS $$
DECLARE ttl bigint;
BEGIN
 SELECT a.evidence_ttl_seconds INTO ttl FROM fs.isolated_policy_activation a
 JOIN fs.isolated_quota_policy_draft d USING(tenant_id,revision)
 JOIN fs.isolated_quota_policy_review r USING(tenant_id,revision)
 WHERE a.tenant_id=t AND a.revision=rev AND a.quota_limit=d.quota_limit
 AND a.evidence_ttl_seconds=d.evidence_ttl_seconds AND a.actor<>d.author AND a.actor<>r.reviewer
 AND a.activated_at>=r.reviewed_at AND a.activated_at<=statement_timestamp()
 AND a.revision=(SELECT max(x.revision) FROM fs.isolated_policy_activation x WHERE x.tenant_id=t)
 AND NOT EXISTS(SELECT 1 FROM fs.isolated_quota_fence x WHERE x.tenant_id=t AND x.enabled_revision IS NOT NULL);
 IF ttl IS NULL THEN RAISE EXCEPTION 'current reviewed historical policy required' USING ERRCODE='PQU01'; END IF;
 RETURN ttl;
END; $$;

CREATE FUNCTION fs.capture_accounting_manifest(t uuid,mid uuid,rev bigint) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs SET lock_timeout='2s' SET timezone='UTC' AS $$
DECLARE principal fs.accounting_manifest_principal%ROWTYPE; existing fs.accounting_manifest%ROWTYPE;
 n integer; ttl bigint; captured timestamptz;
BEGIN
 SELECT * INTO principal FROM fs.accounting_manifest_principal
 WHERE login_role=session_user AND tenant_id=t AND capability='CAPTURE';
 IF NOT FOUND THEN RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501'; END IF;
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
 captured:=clock_timestamp();
 INSERT INTO fs.accounting_manifest VALUES(mid,t,rev,principal.actor,captured,
  captured+make_interval(secs=>ttl::double precision),n,fs.accounting_untracked(t));
 INSERT INTO fs.accounting_manifest_entry
 SELECT t,mid,rev,s.session_id,s.evidence FROM fs.accounting_source_entries(t) s;
 RETURN mid;
END; $$;

CREATE FUNCTION fs.review_accounting_manifest(t uuid,mid uuid,rev bigint,decision text) RETURNS uuid
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs SET lock_timeout='2s' SET timezone='UTC' AS $$
DECLARE principal fs.accounting_manifest_principal%ROWTYPE; m fs.accounting_manifest%ROWTYPE;
 prior fs.accounting_manifest_review%ROWTYPE;
BEGIN
 SELECT * INTO principal FROM fs.accounting_manifest_principal
 WHERE login_role=session_user AND tenant_id=t AND capability='REVIEW';
 IF NOT FOUND THEN RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501'; END IF;
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
 IF m.valid_until<=clock_timestamp() OR m.untracked_versions IS DISTINCT FROM fs.accounting_untracked(t)
 OR EXISTS(
  (SELECT s.session_id,s.evidence FROM fs.accounting_source_entries(t) s
   EXCEPT SELECT e.session_id,e.evidence FROM fs.accounting_manifest_entry e WHERE e.manifest_id=mid)
  UNION ALL
  (SELECT e.session_id,e.evidence FROM fs.accounting_manifest_entry e WHERE e.manifest_id=mid
   EXCEPT SELECT s.session_id,s.evidence FROM fs.accounting_source_entries(t) s)
 ) THEN RAISE EXCEPTION 'stale accounting manifest' USING ERRCODE='PQU01'; END IF;
 INSERT INTO fs.accounting_manifest_review VALUES(t,mid,rev,principal.actor,clock_timestamp(),decision);
 RETURN mid;
END; $$;
-- No automatic role creation or production grants. Restricted isolated provisioning is explicit.
REVOKE ALL ON fs.accounting_manifest_principal,fs.accounting_manifest,fs.accounting_manifest_entry,
 fs.accounting_manifest_review FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION fs.lock_accounting_sources(),fs.accounting_source_entries(uuid),
 fs.accounting_untracked(uuid),fs.accounting_policy_ttl(uuid,bigint),
 fs.capture_accounting_manifest(uuid,uuid,bigint),fs.review_accounting_manifest(uuid,uuid,bigint,text) FROM PUBLIC;

-- Scoped inspection, without granting table SELECT or accepting a caller-supplied actor.
CREATE FUNCTION fs.read_accounting_manifest(t uuid,mid uuid) RETURNS jsonb
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,fs SET timezone='UTC' AS $$
DECLARE result jsonb;
BEGIN
 IF NOT EXISTS(SELECT 1 FROM fs.accounting_manifest_principal
  WHERE login_role=session_user AND tenant_id=t) THEN
  RAISE EXCEPTION 'manifest access denied' USING ERRCODE='42501';
 END IF;
 SELECT jsonb_build_object('manifest',to_jsonb(m),'entries',
  COALESCE((SELECT jsonb_agg(jsonb_build_object('sessionId',e.session_id,'evidence',e.evidence) ORDER BY e.session_id)
    FROM fs.accounting_manifest_entry e WHERE e.manifest_id=m.manifest_id),'[]'::jsonb),
  'review',(SELECT to_jsonb(r) FROM fs.accounting_manifest_review r WHERE r.manifest_id=m.manifest_id))
 INTO result FROM fs.accounting_manifest m WHERE m.tenant_id=t AND m.manifest_id=mid;
 IF result IS NULL THEN RAISE EXCEPTION 'manifest not found' USING ERRCODE='PQU01'; END IF;
 RETURN result;
END; $$;
REVOKE EXECUTE ON FUNCTION fs.read_accounting_manifest(uuid,uuid) FROM PUBLIC;
