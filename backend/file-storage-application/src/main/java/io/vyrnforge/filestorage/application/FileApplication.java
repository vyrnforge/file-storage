package io.vyrnforge.filestorage.application;

import io.vyrnforge.filestorage.domain.StorageModel;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.file.FileReference;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Internal application use cases. No HTTP endpoint or trust establishment here.
 * The composition root must supply verified identity and a real authorization port.
 * No object-store operations run inside the transaction.
 */
public final class FileApplication {
    public enum Action { INITIATE, READ_METADATA, READ_SESSION, UPLOAD_TRANSFER }
    public enum Code { DENIED, NOT_FOUND, NO_PLACEMENT, CONFLICT, IN_PROGRESS, INVALID }
    public static final class Failure extends RuntimeException {
        private final Code code;
        public Failure(Code code){super(code.name());this.code=code;}
        public Code code(){return code;}
    }
    private static String text(String value) {
        if(value==null || value.isBlank())throw new Failure(Code.INVALID);
        return value;
    }
    /** Internal context, never a consumer request DTO. FS-110 establishes trust. */
    public record Caller(TenantId tenant,String actor,String consumer) {
        public Caller {Objects.requireNonNull(tenant);text(actor);text(consumer);}
    }
    public record Initiate(String purpose,String name,long expectedSize,String mimeType,String requestKey) {
        public Initiate {
            text(purpose);text(name);text(mimeType);text(requestKey);
            if(expectedSize<0 || name.indexOf('/')>=0 || name.indexOf('\\')>=0
                    || name.codePoints().anyMatch(Character::isISOControl))throw new Failure(Code.INVALID);
        }
    }
    /** Actor-bound idempotency identity for initiation only. */
    public record RequestIdentity(Caller caller,String key) {
        public RequestIdentity {Objects.requireNonNull(caller);text(key);}
    }
    public record Resource(FileId file,TenantId tenant,String ownerConsumer,String purpose,String ownerActor) {
        public Resource {Objects.requireNonNull(file);Objects.requireNonNull(tenant);text(ownerConsumer);text(purpose);text(ownerActor);}
    }
    public interface Authorization {
        boolean mayInitiate(Caller caller,String purpose);
        boolean mayAccess(Caller caller,Action action,Resource resource);
    }
    public record Configuration(Policy policy,Map<LocationId,Location> locations,
                                Map<BackendId,Backend> backends,Set<LocationId> eligible) {
        public Configuration {
            Objects.requireNonNull(policy);locations=Map.copyOf(locations);
            backends=Map.copyOf(backends);eligible=Set.copyOf(eligible);
        }
    }
    /** Internal aggregate; never serialize into consumer API. */
    public record Entry(File file,Version version,UploadSession session,RequestIdentity request,Initiate command) {
        public Entry {
            Objects.requireNonNull(file);Objects.requireNonNull(version);Objects.requireNonNull(session);
            Objects.requireNonNull(request);Objects.requireNonNull(command);
            if(!file.id().equals(version.fileId()) || !file.tenant().equals(version.tenant())
                    || !file.id().equals(session.fileId()) || !version.id().equals(session.versionId())
                    || !file.tenant().equals(request.caller().tenant()) || !session.scope().tenant().equals(file.tenant())
                    || !session.scope().consumer().equals(request.caller().consumer())
                    || !session.scope().purpose().equals(command.purpose())
                    || !session.expectedObject().equals(version.locator())
                    || !request.key().equals(command.requestKey()))
                throw new Failure(Code.INVALID);
        }
        Resource resource(){return new Resource(file.id(),file.tenant(),request.caller().consumer(),command.purpose(),request.caller().actor());}
    }
    /**
     * Future DB implementation must ensure one consistent configuration snapshot,
     * tenant-scoped queries, unique request/file/version/session identities and atomic insertion.
     * Callback exceptions roll back; no external I/O or retries with side effects.
     */
    public interface Transactions {
        <T> T execute(Function<Unit,T> work);

        /** Separate replay path cannot create a new aggregate. Durable adapters coordinate leases. */
        default View initiate(RequestIdentity identity, Initiate command,
                              Function<Unit, View> work, Function<Unit, View> replay) {
            return execute(work);
        }

        default View initiate(RequestIdentity identity, Initiate command, CorrelationContext correlation,
                              Function<Unit, View> work, Function<Unit, View> replay) {
            return initiate(identity, command, work, replay);
        }
    }
    public interface Unit {
        Optional<Configuration> configuration(Scope scope);
        Optional<Entry> byRequest(RequestIdentity identity);
        Optional<Entry> byFile(TenantId tenant,FileId file);
        Optional<Entry> bySession(TenantId tenant,SessionId session);
        void insert(Entry entry);
    }
    /** Safe consumer projection: no physical key, bucket, provider or credential. */
    public record View(FileReference file,VersionId version,FileState fileState,
                       VersionState versionState,SessionId session,UploadState uploadState,
                       Instant expiresAt) {}

    /** Metadata permission alone does not disclose the upload session. */
    public record MetadataView(FileReference file,VersionId version,FileState fileState,VersionState versionState,String name,long declaredSize,String declaredMimeType) {}

    private final Transactions transactions;
    private final Authorization authorization;
    private final Clock clock;
    private final Duration sessionTtl;
    private final Supplier<UUID> ids;
    public FileApplication(Transactions transactions,Authorization authorization,Clock clock,Duration sessionTtl) {
        this(transactions,authorization,clock,sessionTtl,UuidV7::generate);
    }
    public FileApplication(Transactions transactions,Authorization authorization,Clock clock,Duration sessionTtl,Supplier<UUID> ids) {
        this.transactions=Objects.requireNonNull(transactions);this.authorization=Objects.requireNonNull(authorization);
        this.clock=Objects.requireNonNull(clock);this.sessionTtl=Objects.requireNonNull(sessionTtl);this.ids=Objects.requireNonNull(ids);
        if(sessionTtl.isZero()||sessionTtl.isNegative())throw new Failure(Code.INVALID);
    }
    private UUID id(){
        UUID id=Objects.requireNonNull(ids.get());
        if(id.version()!=7 || id.variant()!=2)throw new Failure(Code.INVALID);
        return id;
    }
    public View initiate(Caller caller,Initiate command) {
        return initiate(caller, command, CorrelationContext.create());
    }
    public View initiate(Caller caller, Initiate command, CorrelationContext correlation) {
        Objects.requireNonNull(correlation);
        if(caller==null)throw new Failure(Code.DENIED);
        Objects.requireNonNull(command);
        if(!authorization.mayInitiate(caller,command.purpose()))throw new Failure(Code.DENIED);
        var identity=new RequestIdentity(caller,command.requestKey());
        var scope=new Scope(caller.tenant(),caller.consumer(),command.purpose());
        return transactions.initiate(identity, command, correlation, unit->{
            if (!authorization.mayInitiate(caller, command.purpose())) throw new Failure(Code.DENIED);
            var existing=unit.byRequest(identity);
            if(existing.isPresent()){
                return replay(caller, identity, command, existing.get());
            }
            Configuration c=unit.configuration(scope).orElseThrow(()->new Failure(Code.NO_PLACEMENT));
            Placement placement;
            try { placement=StorageModel.select(scope,c.policy(),c.locations(),c.backends(),c.eligible()); }
            catch(IllegalStateException ex){throw new Failure(Code.NO_PLACEMENT);}
            var location=c.locations().get(placement.location());
            var file=File.create(new FileId(id().toString()),caller.tenant(),command.name());
            var versionId=new VersionId(id());
            String prefix=location.prefix();
            if(!prefix.isEmpty()&&!prefix.endsWith("/"))prefix+="/";
            var locator=new Locator(location.id(),prefix+id(),null);
            var version=Version.pending(file,versionId,locator);
            Instant now=clock.instant();
            var session=UploadSession.initiate(new SessionId(id()),file,versionId,scope,placement,locator,now.plus(sessionTtl),now);
            var entry=new Entry(file,version,session,identity,command);
            unit.insert(entry);
            return view(entry,now);
        }, unit -> replay(caller, identity, command,
                unit.byRequest(identity).orElseThrow(() -> new Failure(Code.NOT_FOUND))));
    }
    private View replay(Caller caller, RequestIdentity identity, Initiate command, Entry entry) {
        if (!entry.request().equals(identity) || !authorization.mayInitiate(caller, command.purpose())
                || !authorization.mayAccess(caller, Action.READ_SESSION, entry.resource())) {
            throw new Failure(Code.DENIED);
        }
        if (!entry.command().equals(command)) throw new Failure(Code.CONFLICT);
        return view(entry, clock.instant());
    }
    public MetadataView metadata(Caller caller,FileId file) {
        if(caller==null)throw new Failure(Code.DENIED);
        Objects.requireNonNull(file);
        return transactions.execute(unit->{
            Entry e=unit.byFile(caller.tenant(),file).orElseThrow(()->new Failure(Code.NOT_FOUND));
            if(!e.file().id().equals(file))throw new Failure(Code.NOT_FOUND);
            requireAccess(caller,e,Action.READ_METADATA);
            return new MetadataView(e.file().reference(),e.version().id(),e.file().state(),e.version().state(),e.file().name(),e.command().expectedSize(),e.command().mimeType());
        });
    }
    public View session(Caller caller,SessionId session) {
        if(caller==null)throw new Failure(Code.DENIED);
        Objects.requireNonNull(session);
        return transactions.execute(unit->{
            Entry e=unit.bySession(caller.tenant(),session).orElseThrow(()->new Failure(Code.NOT_FOUND));
            if(!e.session().id().equals(session))throw new Failure(Code.NOT_FOUND);
            requireAccess(caller,e,Action.READ_SESSION);
            return view(e,clock.instant());
        });
    }
    /** Internal authorization only. No transfer grant is inferred from metadata/session reads. */
    public void authorizeUpload(Caller caller, SessionId session) {
        if (caller == null) throw new Failure(Code.DENIED);
        Objects.requireNonNull(session);
        transactions.execute(unit -> {
            Entry e = unit.bySession(caller.tenant(), session).orElseThrow(() -> new Failure(Code.DENIED));
            if (!session.equals(e.session().id()) || !caller.equals(e.request().caller())
                    || e.file().state() != FileState.ACTIVE || e.version().state() != VersionState.PENDING
                    || e.session().state() != UploadState.INITIATED || !clock.instant().isBefore(e.session().expires()))
                throw new Failure(Code.DENIED);
            requireAccess(caller, e, Action.UPLOAD_TRANSFER);
            return null;
        });
    }

    /** Start or replay completion while the exact initiating session is open. */
    public void authorizeCompletion(Caller caller, SessionId session) {
        if (caller == null) throw new Failure(Code.DENIED);
        Objects.requireNonNull(session);
        transactions.execute(unit -> {
            Entry e = unit.bySession(caller.tenant(), session).orElseThrow(() -> new Failure(Code.DENIED));
            boolean pending = e.version().state() == VersionState.PENDING && e.session().state() == UploadState.INITIATED;
            boolean verifying = e.version().state() == VersionState.VERIFYING && e.session().state() == UploadState.VERIFYING;
            if (!session.equals(e.session().id()) || !caller.equals(e.request().caller())
                    || e.file().state() != FileState.ACTIVE || !(pending || verifying)
                    || !clock.instant().isBefore(e.session().expires())) throw new Failure(Code.DENIED);
            requireAccess(caller, e, Action.UPLOAD_TRANSFER);
            return null;
        });
    }

    private void requireAccess(Caller caller,Entry e,Action action) {
        if(!e.file().tenant().equals(caller.tenant()) || !authorization.mayAccess(caller,action,e.resource()))
            throw new Failure(Code.NOT_FOUND);
    }
    private static View view(Entry e,Instant now){
        UploadState state=e.session().state();
        if((state==UploadState.INITIATED||state==UploadState.UPLOADING||state==UploadState.VERIFYING)
                && !now.isBefore(e.session().expires()))state=UploadState.EXPIRED;
        return new View(e.file().reference(),e.version().id(),e.file().state(),e.version().state(),
                e.session().id(),state,e.session().expires());
    }
}
