-- Service-owned metadata only. No provider secrets, bytes or Kernel reliability tables.
CREATE TABLE fs.backend (
    backend_id uuid PRIMARY KEY,
    provider text NOT NULL CHECK (provider IN ('S3', 'OSS')),
    endpoint text NOT NULL CHECK (endpoint LIKE 'https://%'),
    region text NOT NULL CHECK (btrim(region) <> ''),
    addressing text NOT NULL CHECK (addressing IN ('AUTO', 'PATH', 'VIRTUAL_HOST', 'CNAME'))
);

CREATE TABLE fs.backend_revision (
    backend_id uuid NOT NULL REFERENCES fs.backend,
    config_revision bigint NOT NULL CHECK (config_revision > 0),
    credential_ref text NOT NULL CHECK (credential_ref ~ '^ref:[A-Za-z0-9/_:.-]+$'),
    status text NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'DRAINING', 'RETIRED')),
    validated_revision bigint,
    PRIMARY KEY (backend_id, config_revision),
    CHECK (validated_revision IS NULL OR validated_revision = config_revision),
    CHECK (status <> 'ACTIVE' OR validated_revision IS NOT NULL)
);

CREATE UNIQUE INDEX backend_one_active_revision
    ON fs.backend_revision (backend_id) WHERE status = 'ACTIVE';

CREATE TABLE fs.storage_location (
    location_id uuid PRIMARY KEY,
    backend_id uuid NOT NULL REFERENCES fs.backend,
    bucket text NOT NULL CHECK (btrim(bucket) <> ''),
    prefix text NOT NULL,
    UNIQUE (location_id, backend_id)
);

CREATE TABLE fs.policy_revision (
    tenant_id uuid NOT NULL CHECK (tenant_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab]'),
    consumer varchar(128) NOT NULL CHECK (btrim(consumer) <> ''),
    purpose varchar(128) NOT NULL CHECK (btrim(purpose) <> ''),
    policy_revision bigint NOT NULL CHECK (policy_revision > 0),
    PRIMARY KEY (tenant_id, consumer, purpose, policy_revision)
);

CREATE TABLE fs.active_policy (
    tenant_id uuid NOT NULL,
    consumer varchar(128) NOT NULL,
    purpose varchar(128) NOT NULL,
    policy_revision bigint NOT NULL,
    PRIMARY KEY (tenant_id, consumer, purpose),
    FOREIGN KEY (tenant_id, consumer, purpose, policy_revision)
        REFERENCES fs.policy_revision
);

CREATE TABLE fs.policy_rule (
    tenant_id uuid NOT NULL,
    consumer varchar(128) NOT NULL,
    purpose varchar(128) NOT NULL,
    policy_revision bigint NOT NULL,
    location_id uuid NOT NULL REFERENCES fs.storage_location,
    priority integer NOT NULL CHECK (priority >= 0),
    eligible boolean NOT NULL,
    PRIMARY KEY (tenant_id, consumer, purpose, policy_revision, location_id),
    FOREIGN KEY (tenant_id, consumer, purpose, policy_revision)
        REFERENCES fs.policy_revision
);

CREATE TABLE fs.file (
    file_id varchar(512) PRIMARY KEY CHECK (btrim(file_id) <> ''),
    tenant_id uuid NOT NULL CHECK (tenant_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab]'),
    name varchar(512) NOT NULL CHECK (btrim(name) <> ''),
    state text NOT NULL CHECK (state = 'ACTIVE'),
    UNIQUE (tenant_id, file_id)
);

CREATE TABLE fs.file_version (
    version_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    file_id varchar(512) NOT NULL,
    location_id uuid NOT NULL REFERENCES fs.storage_location,
    object_key text NOT NULL CHECK (octet_length(object_key) BETWEEN 1 AND 1024),
    state text NOT NULL CHECK (state = 'PENDING'),
    UNIQUE (location_id, object_key),
    UNIQUE (tenant_id, file_id, version_id, location_id),
    FOREIGN KEY (tenant_id, file_id) REFERENCES fs.file (tenant_id, file_id)
);

CREATE TABLE fs.upload_session (
    session_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    file_id varchar(512) NOT NULL,
    version_id uuid NOT NULL UNIQUE,
    location_id uuid NOT NULL,
    backend_id uuid NOT NULL,
    config_revision bigint NOT NULL,
    policy_revision bigint NOT NULL,
    consumer varchar(128) NOT NULL,
    purpose varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL,
    expires_nano_remainder smallint NOT NULL CHECK (expires_nano_remainder BETWEEN 0 AND 999),
    state text NOT NULL CHECK (state = 'INITIATED'),
    UNIQUE (tenant_id, session_id, consumer),
    UNIQUE (tenant_id, file_id),
    FOREIGN KEY (tenant_id, file_id, version_id, location_id)
        REFERENCES fs.file_version (tenant_id, file_id, version_id, location_id),
    FOREIGN KEY (location_id, backend_id) REFERENCES fs.storage_location (location_id, backend_id),
    FOREIGN KEY (backend_id, config_revision) REFERENCES fs.backend_revision,
    FOREIGN KEY (tenant_id, consumer, purpose, policy_revision, location_id)
        REFERENCES fs.policy_rule
);

CREATE TABLE fs.initiation_request (
    tenant_id uuid NOT NULL,
    actor varchar(128) NOT NULL CHECK (btrim(actor) <> ''),
    consumer varchar(128) NOT NULL,
    request_key varchar(128) NOT NULL CHECK (btrim(request_key) <> ''),
    session_id uuid NOT NULL UNIQUE,
    expected_size bigint NOT NULL CHECK (expected_size >= 0),
    declared_mime_type varchar(255) NOT NULL CHECK (btrim(declared_mime_type) <> ''),
    PRIMARY KEY (tenant_id, actor, consumer, request_key),
    FOREIGN KEY (tenant_id, session_id, consumer)
        REFERENCES fs.upload_session (tenant_id, session_id, consumer)
);

-- Schema groundwork; authoritative grant administration/read adapter deferred.
CREATE TABLE fs.file_grant (
    grant_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL CHECK (tenant_id::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab]'),
    actor varchar(128) NOT NULL CHECK (btrim(actor) <> ''),
    consumer varchar(128) NOT NULL CHECK (btrim(consumer) <> ''),
    purpose varchar(128) NOT NULL CHECK (btrim(purpose) <> ''),
    action text NOT NULL CHECK (action IN ('INITIATE', 'READ_METADATA', 'READ_SESSION')),
    scope text NOT NULL CHECK (scope IN ('INITIATE_PURPOSE', 'OWNED_FILE', 'EXACT_FILE')),
    file_id varchar(512),
    not_before timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked boolean NOT NULL DEFAULT false,
    CHECK (expires_at > not_before),
    CHECK ((scope = 'EXACT_FILE') = (file_id IS NOT NULL)),
    CHECK ((scope = 'INITIATE_PURPOSE') = (action = 'INITIATE')),
    FOREIGN KEY (tenant_id, file_id) REFERENCES fs.file (tenant_id, file_id)
);

CREATE INDEX file_grant_actor_lookup ON fs.file_grant (tenant_id, actor, consumer);
CREATE INDEX upload_session_expiry ON fs.upload_session (expires_at, expires_nano_remainder);

-- Physical identities are immutable; create a new backend/location for migration.
CREATE FUNCTION fs.reject_physical_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'physical storage identity is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER immutable_backend
    BEFORE UPDATE ON fs.backend
    FOR EACH ROW EXECUTE FUNCTION fs.reject_physical_change();

CREATE TRIGGER immutable_location
    BEFORE UPDATE ON fs.storage_location
    FOR EACH ROW EXECUTE FUNCTION fs.reject_physical_change();
