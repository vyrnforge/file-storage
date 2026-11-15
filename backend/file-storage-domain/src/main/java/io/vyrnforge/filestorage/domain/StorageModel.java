package io.vyrnforge.filestorage.domain;

import io.vyrnforge.platform.kernel.file.FileReference;
import java.net.URI;
import java.time.Instant;
import java.util.*;

 /** Pure domain values. Not a persistence or authorization boundary. */
public final class StorageModel {
    private StorageModel() {}
    static String text(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("blank value");
        return s;
    }
    static long revision(long r) {
        if (r < 1) throw new IllegalArgumentException("revision must be positive");
        return r;
    }
    public record FileId(String value) {
        public FileId {
            text(value);
            if (value.codePointCount(0, value.length()) > 512)
                throw new IllegalArgumentException("file ID exceeds FileReference contract length");
        }
    }
    public record TenantId(UUID value) {
        public TenantId {
            Objects.requireNonNull(value);
            if (value.version() != 7 || value.variant() != 2) throw new IllegalArgumentException("tenant must be UUIDv7");
        }
    }
    public record VersionId(UUID value) { public VersionId { Objects.requireNonNull(value); } }
    public record SessionId(UUID value) { public SessionId { Objects.requireNonNull(value); } }
    public record BackendId(UUID value) { public BackendId { Objects.requireNonNull(value); } }
    public record LocationId(UUID value) { public LocationId { Objects.requireNonNull(value); } }
    public record CredentialRef(String value) {
        public CredentialRef {
            text(value);
            if (!value.matches("ref:[A-Za-z0-9/_:.-]+")) throw new IllegalArgumentException("expected opaque credential reference");
        }
        @Override public String toString() { return "CredentialRef[redacted]"; }
    }
    public enum Provider { S3, OSS }
    public enum BackendStatus { DRAFT, ACTIVE, DRAINING, RETIRED }
    public enum Addressing { AUTO, PATH, VIRTUAL_HOST, CNAME }
    public record Target(Provider provider, URI endpoint, String region, Addressing addressing) {
        public Target {
            Objects.requireNonNull(provider); Objects.requireNonNull(endpoint); text(region); Objects.requireNonNull(addressing);
            if (!"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null)
                throw new IllegalArgumentException("invalid HTTPS endpoint");
        }
    }
    public record Backend(BackendId id, Target target, CredentialRef credential, long configRevision,
                          BackendStatus status, Long validatedRevision) {
        public Backend {
            Objects.requireNonNull(id); Objects.requireNonNull(target); Objects.requireNonNull(credential);
            revision(configRevision); Objects.requireNonNull(status);
            if (validatedRevision != null && validatedRevision != configRevision) throw new IllegalArgumentException("stale validation");
            if (status == BackendStatus.ACTIVE && validatedRevision == null) throw new IllegalArgumentException("active requires validation");
        }
        public static Backend draft(BackendId id, Target target, CredentialRef ref) {
            return new Backend(id,target,ref,1,BackendStatus.DRAFT,null);
        }
        public Backend validate(long exactRevision) {
            if (status != BackendStatus.DRAFT || exactRevision != configRevision) throw new IllegalStateException("invalid validation");
            return new Backend(id,target,credential,configRevision,status,exactRevision);
        }
        public Backend activate() {
            if (status != BackendStatus.DRAFT || validatedRevision == null) throw new IllegalStateException("not validated");
            return new Backend(id,target,credential,configRevision,BackendStatus.ACTIVE,validatedRevision);
        }
        /** Credential update creates a candidate. Current active snapshot is retained externally until activation. */
        public Backend reviseCredential(CredentialRef next) {
            if (status == BackendStatus.RETIRED || status == BackendStatus.DRAINING) throw new IllegalStateException("not editable");
            return new Backend(id,target,next,Math.incrementExact(configRevision),BackendStatus.DRAFT,null);
        }
        public Backend drain() {
            if (status != BackendStatus.ACTIVE) throw new IllegalStateException("not active");
            return new Backend(id,target,credential,configRevision,BackendStatus.DRAINING,validatedRevision);
        }
        public Backend retire(long liveReferences, long sessions, long jobs) {
            if (status != BackendStatus.DRAINING || liveReferences != 0 || sessions != 0 || jobs != 0)
                throw new IllegalStateException("retirement blocked");
            return new Backend(id,target,credential,configRevision,BackendStatus.RETIRED,validatedRevision);
        }
        public boolean permitsExistingAccess() { return status == BackendStatus.ACTIVE || status == BackendStatus.DRAINING; }
        /** Compare revisions when replacing the registry entry; physical target cannot be silently changed. */
        public void requireSamePhysicalTarget(Backend next) {
            if (!id.equals(next.id) || !target.equals(next.target)) throw new IllegalArgumentException("physical target changed");
        }
    }
    public record Location(LocationId id, BackendId backend, String bucket, String prefix) {
        public Location { Objects.requireNonNull(id); Objects.requireNonNull(backend); text(bucket); Objects.requireNonNull(prefix); }
        public void requireSamePhysicalLocation(Location next) {
            if (!equals(next)) throw new IllegalArgumentException("physical location changed");
        }
    }
    public record Locator(LocationId location, String objectKey, String providerVersion) {
        public Locator { Objects.requireNonNull(location); text(objectKey); if (providerVersion != null) text(providerVersion); }
    }
    /** Caller scope is already validated by a future authorization boundary. */
    public record Scope(TenantId tenant, String consumer, String purpose) {
        public Scope { Objects.requireNonNull(tenant); text(consumer); text(purpose); }
    }
    public record Rule(Scope scope, LocationId location, int priority) {
        public Rule { Objects.requireNonNull(scope); Objects.requireNonNull(location); if(priority < 0) throw new IllegalArgumentException("priority"); }
    }
    public record Policy(long revision, List<Rule> rules) {
        public Policy { StorageModel.revision(revision); rules=List.copyOf(rules); }
    }
    public record Placement(Scope scope, LocationId location, long policyRevision, long configRevision) {
        public Placement { Objects.requireNonNull(scope); Objects.requireNonNull(location); revision(policyRevision); revision(configRevision); }
    }
    public static Placement select(Scope scope, Policy policy, Map<LocationId,Location> locations,
                                   Map<BackendId,Backend> backends, Set<LocationId> authorized) {
        Objects.requireNonNull(scope); Objects.requireNonNull(policy);
        var matches=policy.rules.stream().filter(r -> r.scope.equals(scope)).toList();
        if (matches.isEmpty()) throw new IllegalStateException("no matching policy");
        int priority=matches.stream().mapToInt(Rule::priority).min().orElseThrow();
        var best=matches.stream().filter(r -> r.priority == priority).toList();
        if (best.size()!=1) throw new IllegalStateException("ambiguous policy");
        LocationId id=best.getFirst().location;
        Location loc=locations.get(id);
        if (!authorized.contains(id) || loc==null || !id.equals(loc.id)) throw new IllegalStateException("location not eligible");
        Backend backend=backends.get(loc.backend);
        if (backend==null || !loc.backend.equals(backend.id) || backend.status!=BackendStatus.ACTIVE)
            throw new IllegalStateException("backend not active");
        return new Placement(scope,id,policy.revision,backend.configRevision);
    }
    public enum FileState { ACTIVE, SOFT_DELETED, PURGE_PENDING, PURGED }
    public record File(FileId id, TenantId tenant, String name, FileState state) {
        public File { Objects.requireNonNull(id); Objects.requireNonNull(tenant); text(name); Objects.requireNonNull(state); }
        public static File create(FileId id,TenantId tenant,String name) { return new File(id,tenant,name,FileState.ACTIVE); }
        public FileReference reference() { return new FileReference(id.value,tenant.value,"file-storage"); }
        public File softDelete() { return transition(FileState.ACTIVE,FileState.SOFT_DELETED); }
        public File restore() { return transition(FileState.SOFT_DELETED,FileState.ACTIVE); }
        public File requestPurge(boolean retentionSatisfied, boolean held) {
            if (!retentionSatisfied || held) throw new IllegalStateException("purge prohibited");
            return transition(FileState.SOFT_DELETED,FileState.PURGE_PENDING);
        }
        public File confirmPurge(boolean deletionVerified) {
            if (!deletionVerified) throw new IllegalStateException("deletion not verified");
            return transition(FileState.PURGE_PENDING,FileState.PURGED);
        }
        private File transition(FileState from,FileState to) {
            if (state!=from) throw new IllegalStateException("invalid file transition");
            return new File(id,tenant,name,to);
        }
    }
    public enum VersionState { PENDING, VERIFYING, AVAILABLE, QUARANTINED, FAILED }
    public record Content(long size,String sha256,String mimeType) {
        public Content { if(size<0 || sha256==null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid content evidence"); text(mimeType); }
    }
    public enum Scan { CLEAN, MALICIOUS, UNAVAILABLE }
    public static final class Version {
        private final FileId file; private final TenantId tenant; private final VersionId id;
        private final Locator locator; private final VersionState state; private final Content content;
        private Version(FileId file,TenantId tenant,VersionId id,Locator locator,VersionState state,Content content) {
            this.file=Objects.requireNonNull(file); this.tenant=Objects.requireNonNull(tenant); this.id=Objects.requireNonNull(id);
            this.locator=Objects.requireNonNull(locator); this.state=state; this.content=content;
        }
        public static Version pending(File file, VersionId id, Locator locator) {
            if(file.state!=FileState.ACTIVE) throw new IllegalStateException("file not active");
            return new Version(file.id,file.tenant,id,locator,VersionState.PENDING,null);
        }
        /** Restore the persisted lifecycle, never infer a scan verdict from a state. */
        public static Version restore(File file, VersionId id, Locator locator, VersionState state, Content content) {
            Objects.requireNonNull(state);
            if ((state == VersionState.AVAILABLE || state == VersionState.QUARANTINED) != (content != null))
                throw new IllegalArgumentException("invalid persisted content");
            return new Version(file.id,file.tenant,id,locator,state,content);
        }
        public Version verify() { require(VersionState.PENDING); return copy(locator,VersionState.VERIFYING,null); }
        public Version finish(Content verified, Scan scan) {
            require(VersionState.VERIFYING); Objects.requireNonNull(verified); Objects.requireNonNull(scan);
            return copy(locator, scan==Scan.CLEAN?VersionState.AVAILABLE:VersionState.QUARANTINED,verified);
        }
        public Version fail() {
            if(state!=VersionState.PENDING && state!=VersionState.VERIFYING) throw new IllegalStateException("invalid failure");
            return copy(locator,VersionState.FAILED,null);
        }
        /** Caller supplies independently verified destination content; persistence CAS is later work. */
        public Version migrate(Locator destination, Content verifiedDestination) {
            require(VersionState.AVAILABLE);
            if(!content.equals(verifiedDestination)) throw new IllegalArgumentException("destination mismatch");
            return copy(destination,state,content);
        }
        public boolean availableTo(File owner) {
            return state==VersionState.AVAILABLE && owner.state==FileState.ACTIVE && file.equals(owner.id) && tenant.equals(owner.tenant);
        }
        public FileId fileId(){return file;} public TenantId tenant(){return tenant;} public VersionId id(){return id;}
        public Locator locator(){return locator;} public VersionState state(){return state;} public Optional<Content> content(){return Optional.ofNullable(content);}
        private void require(VersionState expected){if(state!=expected)throw new IllegalStateException("invalid version transition");}
        private Version copy(Locator l,VersionState s,Content c){return new Version(file,tenant,id,l,s,c);}
    }
    public enum UploadState { INITIATED, UPLOADING, VERIFYING, COMPLETED, FAILED, CANCELLED, EXPIRED }
    public static final class UploadSession {
        private final SessionId id; private final FileId file; private final VersionId version; private final Scope scope;
        private final Placement placement; private final Locator expectedObject; private final Instant expires; private final UploadState state;
        private UploadSession(SessionId id,FileId file,VersionId version,Scope scope,Placement placement,Locator expectedObject,Instant expires,UploadState state) {
            this.id=Objects.requireNonNull(id);this.file=Objects.requireNonNull(file);this.version=Objects.requireNonNull(version);
            this.scope=Objects.requireNonNull(scope);this.placement=Objects.requireNonNull(placement);this.expectedObject=Objects.requireNonNull(expectedObject);this.expires=Objects.requireNonNull(expires);this.state=state;
        }
        public static UploadSession initiate(SessionId id,File file,VersionId version,Scope scope,Placement placement,Locator expectedObject,Instant expires,Instant now) {
            if (!expires.isAfter(now)) throw new IllegalArgumentException("invalid session expiry");
            return restoreInitiated(id, file, version, scope, placement, expectedObject, expires);
        }
        /** Rehydrate an already-persisted initiation, even after expiry; never issues a grant. */
        public static UploadSession restoreInitiated(SessionId id, File file, VersionId version, Scope scope,
                                                     Placement placement, Locator expectedObject, Instant expires) {
            if (!file.tenant.equals(scope.tenant) || !scope.equals(placement.scope)
                    || !placement.location.equals(expectedObject.location) || file.state != FileState.ACTIVE) {
                throw new IllegalArgumentException("invalid persisted session");
            }
            return new UploadSession(id, file.id, version, scope, placement, expectedObject, expires, UploadState.INITIATED);
        }
        public static UploadSession restore(SessionId id, File file, VersionId version, Scope scope,
                                            Placement placement, Locator locator, Instant expires, UploadState state) {
            return restoreInitiated(id,file,version,scope,placement,locator,expires).copy(Objects.requireNonNull(state));
        }
        public UploadSession start(Instant now){return advance(UploadState.INITIATED,UploadState.UPLOADING,now);}
        public UploadSession verify(Instant now){return advance(UploadState.UPLOADING,UploadState.VERIFYING,now);}
        public UploadSession complete(Version verified,Instant now){
            if(!verified.file.equals(file)||!verified.tenant.equals(scope.tenant)||!verified.id.equals(version)
                ||!verified.locator.location.equals(expectedObject.location)||!verified.locator.objectKey.equals(expectedObject.objectKey)||verified.state!=VersionState.AVAILABLE)
                throw new IllegalArgumentException("unverified or mismatched version");
            return advance(UploadState.VERIFYING,UploadState.COMPLETED,now);
        }
        public UploadSession cancel(){requireOpen();return copy(UploadState.CANCELLED);}
        public UploadSession fail(){requireOpen();return copy(UploadState.FAILED);}
        public UploadSession expire(Instant now){requireOpen();if(now.isBefore(expires))throw new IllegalStateException("not expired");return copy(UploadState.EXPIRED);}
        private void requireOpen(){if(state!=UploadState.INITIATED&&state!=UploadState.UPLOADING&&state!=UploadState.VERIFYING)throw new IllegalStateException("terminal");}
        private UploadSession advance(UploadState from,UploadState to,Instant now){if(state!=from||!now.isBefore(expires))throw new IllegalStateException("invalid upload transition");return copy(to);}
        private UploadSession copy(UploadState to){return new UploadSession(id,file,version,scope,placement,expectedObject,expires,to);}
        public Placement placement(){return placement;} public Locator expectedObject(){return expectedObject;} public UploadState state(){return state;} public SessionId id(){return id;}
        public FileId fileId(){return file;} public VersionId versionId(){return version;} public Scope scope(){return scope;} public Instant expires(){return expires;}
    }
}
