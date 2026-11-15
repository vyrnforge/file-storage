-- Service-specific work, separate from the Kernel event outbox.
CREATE TABLE fs.storage_operation (
    operation_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    file_id varchar(512) NOT NULL,
    version_id uuid NOT NULL,
    location_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('VERIFY_UPLOAD', 'CLEANUP_UPLOAD', 'PURGE_VERSION', 'MIGRATE_VERSION')),
    operation_key varchar(128) NOT NULL CHECK (btrim(operation_key) <> ''),
    correlation_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SUCCEEDED', 'DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts > 0 AND attempts <= max_attempts),
    available_at timestamptz NOT NULL,
    locked_until timestamptz,
    claim_token uuid,
    last_error_code text CHECK (last_error_code IN ('TRANSIENT', 'PERMANENT', 'ATTEMPTS_EXHAUSTED')),
    created_at timestamptz NOT NULL,
    finished_at timestamptz,
    UNIQUE (tenant_id, kind, operation_key),
    FOREIGN KEY (tenant_id, file_id, version_id, location_id)
        REFERENCES fs.file_version (tenant_id, file_id, version_id, location_id),
    CHECK ((claim_token IS NULL) = (locked_until IS NULL)),
    CHECK (status = 'PENDING' OR claim_token IS NULL),
    CHECK ((status = 'PENDING') = (finished_at IS NULL))
);

CREATE INDEX storage_operation_claim
    ON fs.storage_operation (kind, available_at, operation_id) WHERE status = 'PENDING';
CREATE INDEX storage_operation_dead
    ON fs.storage_operation (tenant_id, finished_at, operation_id) WHERE status = 'DEAD';
