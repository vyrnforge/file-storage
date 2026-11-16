package io.vyrnforge.filestorage.security;

import io.vyrnforge.filestorage.application.FileApplication;
import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.*;
import io.vyrnforge.platform.kernel.identity.*;
import io.vyrnforge.platform.kernel.pipeline.*;
import io.vyrnforge.platform.kernel.ports.*;
import io.vyrnforge.platform.kernel.errors.KernelAccessException;
import java.time.*;
import java.util.*;

/**
 * Only the composition root may wire this boundary. Verifier, binding, membership,
 * entitlement and grant ports are trusted authority dependencies, never request DTOs.
 * No default verifier, permissive authorization, token decoder or live IAM client.
 */
public final class SecuredFiles {
    public enum TokenKind { ACCESS, ID }
    public enum GrantScope { INITIATE_PURPOSE, OWNED_FILE, EXACT_FILE }
    public enum SecurityCode { AUTHENTICATION_FAILED, ACCESS_DENIED }
    public static final class SecurityFailure extends RuntimeException {
        private final SecurityCode code;
        public SecurityFailure(SecurityCode code){super(code.name());this.code=Objects.requireNonNull(code);}
        public SecurityCode code(){return code;}
    }
    private static String text(String value){
        if(value==null||value.isBlank())throw new IllegalArgumentException("required value");
        return value;
    }
    /** Exact deployment trust settings. No inferred defaults. */
    public record Trust(String issuer,String audience){
        public Trust {text(issuer);text(audience);}
    }
    /** Trusted result only: verifier must authenticate signature/key/algorithm and access-token profile. */
    public record VerifiedAccess(String issuer,String subject,Set<String> audiences,TokenKind kind,
                                 Instant notBefore,Instant expiresAt,Instant authenticatedAt) {
        public VerifiedAccess {
            text(issuer);text(subject);audiences=Set.copyOf(audiences);
            Objects.requireNonNull(kind);Objects.requireNonNull(expiresAt);
        }
        @Override public String toString(){return "VerifiedAccess[redacted]";}
    }
    @FunctionalInterface public interface Verifier {
        /** Authenticate opaque bearer credential; never log/store it. No unverified JWT parsing. */
        VerifiedAccess verify(String bearer);
    }
    /** Authoritative binding, not a token claim. Canonical actorId must be globally unique. */
    public record Binding(ActorReference actor,TenantId tenant,String consumer,Set<Action> actions){
        public Binding {Objects.requireNonNull(actor);Objects.requireNonNull(tenant);text(consumer);actions=Set.copyOf(actions);}
    }
    @FunctionalInterface public interface Bindings {
        Optional<Binding> resolve(String issuer,String subject,String audience);
    }
    public record Grant(TenantId tenant,String actor,String consumer,Action action,String purpose,
                        GrantScope scope,FileId file,Instant notBefore,Instant expiresAt,boolean revoked){
        public Grant {
            Objects.requireNonNull(tenant);text(actor);text(consumer);Objects.requireNonNull(action);text(purpose);
            Objects.requireNonNull(scope);Objects.requireNonNull(notBefore);Objects.requireNonNull(expiresAt);
            if(!expiresAt.isAfter(notBefore)
                    || (scope==GrantScope.EXACT_FILE)!=(file!=null)
                    || (scope==GrantScope.INITIATE_PURPOSE)!=(action==Action.INITIATE))
                throw new IllegalArgumentException("invalid grant");
        }
        boolean active(Instant now){return !revoked&&!now.isBefore(notBefore)&&now.isBefore(expiresAt);}
    }
    @FunctionalInterface public interface Grants {
        /** Current authoritative grants; unavailable source must throw, not return permissive defaults. */
        List<Grant> current(Caller caller);
    }
    public record Result<T>(T value,CorrelationContext correlation){}
    private final Trust trust;
    private final Verifier verifier;
    private final Bindings bindings;
    private final TenantMembershipPort membership;
    private final ModuleEntitlementPort entitlement;
    private final Grants grants;
    private final Transactions transactions;
    private final Clock clock;
    private final Duration ttl;

    public SecuredFiles(Trust trust,Verifier verifier,Bindings bindings,TenantMembershipPort membership,
                        ModuleEntitlementPort entitlement,Grants grants,Transactions transactions,Clock clock,Duration ttl){
        this.trust=Objects.requireNonNull(trust);this.verifier=Objects.requireNonNull(verifier);
        this.bindings=Objects.requireNonNull(bindings);this.membership=Objects.requireNonNull(membership);
        this.entitlement=Objects.requireNonNull(entitlement);this.grants=Objects.requireNonNull(grants);
        this.transactions=Objects.requireNonNull(transactions);this.clock=Objects.requireNonNull(clock);this.ttl=Objects.requireNonNull(ttl);
        if(ttl.isNegative()||ttl.isZero())throw new IllegalArgumentException("invalid TTL");
    }
    public Result<View> initiate(String bearer,TenantId requestedTenant,UUID correlationId,Initiate command){
        Objects.requireNonNull(command);
        return execute(bearer,requestedTenant,correlationId,Action.INITIATE,command.purpose(),(app,caller,correlation)->app.initiate(caller,command,correlation));
    }
    public Result<MetadataView> metadata(String bearer,TenantId requestedTenant,UUID correlationId,FileId file){
        Objects.requireNonNull(file);
        return execute(bearer,requestedTenant,correlationId,Action.READ_METADATA,null,(app,caller,correlation)->app.metadata(caller,file));
    }
    public Result<View> session(String bearer,TenantId requestedTenant,UUID correlationId,SessionId session){
        Objects.requireNonNull(session);
        return execute(bearer,requestedTenant,correlationId,Action.READ_SESSION,null,(app,caller,correlation)->app.session(caller,session));
    }
    /** Internal transfer boundary: fresh trusted identity, tenant, entitlement and explicit upload grant. */
    public Result<Caller> authorizeUpload(String bearer, TenantId requestedTenant, UUID correlationId, SessionId session) {
        Objects.requireNonNull(session);
        return execute(bearer, requestedTenant, correlationId, Action.UPLOAD_TRANSFER, null, (app, caller, correlation) -> {
            app.authorizeUpload(caller, session);
            return caller;
        });
    }
    public Result<Caller> authorizeCompletion(String bearer, TenantId tenant, UUID correlationId, SessionId session) {
        return execute(bearer, tenant, correlationId, Action.UPLOAD_TRANSFER, null, (app, caller, correlation) -> {
            app.authorizeCompletion(caller, session);
            return caller;
        });
    }
    public Result<Caller> authorizeDownload(String bearer,TenantId tenant,UUID correlationId,FileId file,VersionId version){
        return execute(bearer,tenant,correlationId,Action.DOWNLOAD_TRANSFER,null,(app,caller,correlation)->{
            app.authorizeDownload(caller,file,version);return caller;
        });
    }
    public Result<CatalogPage> catalog(String bearer,TenantId tenant,UUID correlationId,FileId after,int limit){
        return execute(bearer,tenant,correlationId,Action.LIST_METADATA,null,
            (app,caller,correlation)->app.catalog(caller,catalogGrants(caller),after,limit));
    }
    public Result<DeletedPage> deleted(String bearer,TenantId tenant,UUID correlationId,FileId after,int limit){
        return execute(bearer,tenant,correlationId,Action.LIST_DELETED_METADATA,null,
            (app,caller,correlation)->app.deleted(caller,resourceGrants(caller,Action.LIST_DELETED_METADATA),after,limit));
    }
    public Result<CatalogUsage> usage(String bearer,TenantId tenant,UUID correlationId){
        return execute(bearer,tenant,correlationId,Action.LIST_METADATA,null,
            (app,caller,correlation)->app.usage(caller,catalogGrants(caller)));
    }
    /** Internal review request only; deliberately not exposed by an HTTP route. */
    public Result<PurgePreparation> preparePurge(String bearer,TenantId tenant,UUID correlationId,PurgeRequest request){
        Objects.requireNonNull(request);
        var action=request.supersedes()==null?Action.PREPARE_PURGE:Action.SUPERSEDE_PURGE;
        return execute(bearer,tenant,correlationId,action,null,
            (app,caller,correlation)->app.preparePurge(caller,request,resourceGrants(caller,action),correlation));
    }
    public Result<LifecycleView> lifecycle(String bearer,TenantId tenant,UUID correlationId,FileId file,Action action,long expected){
        if(action!=Action.DELETE_FILE&&action!=Action.RESTORE_FILE)throw new Failure(Code.INVALID);
        return execute(bearer,tenant,correlationId,action,null,
            (app,caller,correlation)->app.lifecycle(caller,file,action,expected,resourceGrants(caller,action),correlation));
    }
    private List<CatalogGrant> catalogGrants(Caller caller){return resourceGrants(caller,Action.LIST_METADATA);}
    private List<CatalogGrant> resourceGrants(Caller caller,Action action){
        try{
            var now=clock.instant();
            var current=List.copyOf(grants.current(caller));
            if(current.size()>4096)throw new IllegalArgumentException("grant scope exceeds bound");
            var result=new ArrayList<CatalogGrant>();
            for(var g:current){
                if(g.action()!=action||!g.active(now)||!g.tenant().equals(caller.tenant())
                    ||!g.actor().equals(caller.actor())||!g.consumer().equals(caller.consumer()))continue;
                if(g.scope()==GrantScope.OWNED_FILE||g.scope()==GrantScope.EXACT_FILE)
                    result.add(new CatalogGrant(g.purpose(),g.file(),g.expiresAt()));
                if(result.size()>256)throw new IllegalArgumentException("catalog scope exceeds bound");
            }
            if(result.isEmpty())throw new IllegalArgumentException("no catalog grant");
            return List.copyOf(result);
        }catch(RuntimeException ex){throw new SecurityFailure(SecurityCode.ACCESS_DENIED);}
    }
    private VerifiedAccess authenticate(String bearer) {
        return AccessAuthentication.verify(trust, verifier, clock, bearer);
    }
    @FunctionalInterface
    private interface Operation<T> {
        T apply(FileApplication app, Caller caller, CorrelationContext correlation);
    }
    private <T> Result<T> execute(String bearer,TenantId requested,UUID correlationId,Action action,String purpose,
                                  Operation<T> operation){
        VerifiedAccess v=authenticate(bearer);
        Binding b;
        try {
            b=bindings.resolve(v.issuer(),v.subject(),trust.audience()).orElseThrow();
            if(requested==null||!requested.equals(b.tenant())||b.actor().actorType()!=ActorType.USER)
                throw new SecurityFailure(SecurityCode.ACCESS_DENIED);
        }catch(RuntimeException ex){throw new SecurityFailure(SecurityCode.ACCESS_DENIED);}
        Caller caller=new Caller(b.tenant(),b.actor().actorId(),b.consumer());
        Authorization auth=authorization(b,caller);
        var pipeline=new RequestContextPipeline<ValidatedIdentityCredential>(
            new ValidatedIdentityAdapter(),membership,
            (tenant,module)->{
                ModuleEntitlement e=entitlement.getEntitlement(tenant,module);
                return e!=null && e.effectiveAt()!=null && clock.instant().isBefore(e.effectiveAt())?null:e;
            },
            (ctx,key,resource)->ctx.actor().equals(b.actor()) && ctx.tenant().tenantId().equals(b.tenant().value())
                && action.name().equals(key) && b.actions().contains(action)
                && (action!=Action.INITIATE||auth.mayInitiate(caller,purpose)));
        var credential=new ValidatedIdentityCredential(b.actor(),AuthorizationProjection.empty(),
                new AuthenticationContext(v.authenticatedAt(),Set.of()));
        var correlation=correlationId==null?CorrelationContext.create():CorrelationContext.create(correlationId);
        ResolvedRequestContext resolved;
        try{
            resolved=pipeline.resolve(new RequestContextInput<>(credential,TenantContext.tenant(requested.value()),
                    correlation,EndpointPolicy.tenantModule("file-storage",action.name()),null));
        }catch(RuntimeException e){throw new SecurityFailure(SecurityCode.ACCESS_DENIED);}
        // Application checks the authoritative file resource after tenant-scoped lookup.
        // The request-local authorization object cannot be reused for another caller.
        var app=new FileApplication(transactions,auth,clock,ttl);
        return new Result<>(operation.apply(app,caller,resolved.correlation()),resolved.correlation());
    }
    private Authorization authorization(Binding binding,Caller expected){
        return new Authorization(){
            public boolean mayInitiate(Caller caller,String purpose){
                return expected.equals(caller)&&binding.actions().contains(Action.INITIATE)
                    &&matches(caller,Action.INITIATE,purpose,null);
            }
            public boolean mayAccess(Caller caller,Action action,Resource resource){
                return expected.equals(caller)&&binding.actions().contains(action)&&resource!=null
                    &&caller.tenant().equals(resource.tenant())&&matches(caller,action,resource.purpose(),resource);
            }
        };
    }
    private boolean matches(Caller caller,Action action,String purpose,Resource resource){
        try{
            List<Grant> current = List.copyOf(grants.current(caller));
            Instant now=clock.instant();
            for(Grant g:current){
                if(!g.active(now)||!g.tenant().equals(caller.tenant())||!g.actor().equals(caller.actor())
                        ||!g.consumer().equals(caller.consumer())||g.action()!=action||!g.purpose().equals(purpose))continue;
                if(g.scope()==GrantScope.INITIATE_PURPOSE&&resource==null)return true;
                if(resource!=null){
                    if(g.scope()==GrantScope.OWNED_FILE&&resource.ownerActor().equals(caller.actor())
                            &&resource.ownerConsumer().equals(caller.consumer()))return true;
                    if(g.scope()==GrantScope.EXACT_FILE&&resource.file().equals(g.file()))return true;
                }
            }
            return false;
        }catch(RuntimeException ex){return false;}
    }
}
