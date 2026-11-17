package io.vyrnforge.filestorage.security;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class SecuredFilesTest {
    static UUID id(long n){return new UUID(0x0190000000007000L,0x8000000000000000L+n);}
    static final TenantId A=new TenantId(id(1)), B=new TenantId(id(2));
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z"), END=NOW.plusSeconds(300);
    static final String ISS="https://iam.example.test", AUD="file-storage-test";
    static final ActorReference ALICE=new ActorReference(ActorType.USER,"actor-a",null,null);
    static final ActorReference BOB=new ActorReference(ActorType.USER,"actor-b",null,null);
    static final Binding ASSET=new Binding(ALICE,A,"asset",Set.of(Action.values()));
    static final Binding SAME=new Binding(BOB,A,"asset",Set.of(Action.values()));
    static final Binding ITSM=new Binding(ALICE,A,"itsm",Set.of(Action.values()));
    static final Scope SCOPE=new Scope(A,"asset","attachment");
    static final BackendId BACK=new BackendId(id(3));static final LocationId LOC=new LocationId(id(4));
    static final Configuration CONFIG=new Configuration(new Policy(1,List.of(new Rule(SCOPE,LOC,0))),
        Map.of(LOC,new Location(LOC,BACK,"synthetic","prefix/")),
        Map.of(BACK,Backend.draft(BACK,new Target(Provider.OSS,URI.create("https://oss.example.test"),"region",Addressing.AUTO),new CredentialRef("ref:synthetic")).validate(1).activate()),Set.of(LOC));
    static final class Memory implements Transactions {
        final List<Entry> rows=new ArrayList<>();
        public <T>T execute(Function<Unit,T> work){
            var staged=new ArrayList<>(rows);
            T result=work.apply(new Unit(){
                public Optional<Configuration> configuration(Scope scope){return Optional.of(CONFIG);}
                public Optional<Entry> byRequest(RequestIdentity r){return staged.stream().filter(e->e.request().equals(r)).findFirst();}
                public Optional<Entry> byFile(TenantId t,FileId f){return staged.stream().filter(e->e.file().tenant().equals(t)&&e.file().id().equals(f)).findFirst();}
                public Optional<Entry> bySession(TenantId t,SessionId s){return staged.stream().filter(e->e.file().tenant().equals(t)&&e.session().id().equals(s)).findFirst();}
                public void insert(Entry e){staged.add(e);}
            });rows.clear();rows.addAll(staged);return result;
        }
    }
    final Memory db=new Memory();
    final List<Grant> grants=new ArrayList<>();
    final Map<String,Binding> bindings=new HashMap<>(Map.of("alice",ASSET,"bob",SAME,"itsm",ITSM));
    final Map<String,VerifiedAccess> tokens=new HashMap<>();
    boolean member=true,enabled=true,storeDown=false;Instant effective=NOW;UUID entitlementTenant=A.value();String module="file-storage";
    SecuredFilesTest(){
        for(String s:bindings.keySet())tokens.put(s,token(s,ISS,Set.of(AUD),TokenKind.ACCESS,NOW,END));
        grants.add(grant(ASSET,Action.INITIATE,GrantScope.INITIATE_PURPOSE,null,NOW,END,false));
        grants.add(grant(ASSET,Action.READ_METADATA,GrantScope.OWNED_FILE,null,NOW,END,false));
        grants.add(grant(ASSET,Action.READ_SESSION,GrantScope.OWNED_FILE,null,NOW,END,false));
    }
    static VerifiedAccess token(String subject,String issuer,Set<String> aud,TokenKind kind,Instant before,Instant expires){
        return new VerifiedAccess(issuer,subject,aud,kind,before,expires,NOW);
    }
    static Grant grant(Binding b,Action a,GrantScope s,FileId f,Instant before,Instant end,boolean revoked){
        return new Grant(b.tenant(),b.actor().actorId(),b.consumer(),a,"attachment",s,f,before,end,revoked);
    }
    SecuredFiles facade(){
        return facade(bearer->{
            VerifiedAccess v=tokens.get(bearer);if(v==null)throw new IllegalArgumentException("synthetic invalid token");return v;
        });
    }
    SecuredFiles facade(Verifier verifier){
        return new SecuredFiles(new Trust(ISS,AUD),verifier,(issuer,subject,aud)->Optional.ofNullable(bindings.get(subject)),(actor,t)->member,
        (t,m)->new ModuleEntitlement(entitlementTenant,module,enabled,effective),
        caller->{if(storeDown)throw new IllegalStateException("synthetic secret detail");return grants;},
        db,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5));
    }
    static Initiate command(){return new Initiate("attachment","sample.txt",5,"text/plain","key");}
    void security(SecurityCode code,org.junit.jupiter.api.function.Executable e){assertEquals(code,assertThrows(SecurityFailure.class,e).code());}
    void app(Code code,org.junit.jupiter.api.function.Executable e){assertEquals(code,assertThrows(Failure.class,e).code());}

    @Test void failureObservationsAreBoundedAndNeverChangeAuthorization(){
        var seen=new ArrayList<SecurityObservation.Reason>();
        var f=new SecuredFiles(new Trust(ISS,AUD),bearer->tokens.get(bearer),
            (issuer,subject,aud)->Optional.ofNullable(bindings.get(subject)),(actor,t)->member,
            (t,m)->new ModuleEntitlement(entitlementTenant,module,enabled,effective),
            caller->{if(storeDown)throw new IllegalStateException("private fixture secret");return grants;},
            db,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5),(boundary,reason)->{
                assertEquals(SecurityObservation.Boundary.FILES,boundary);seen.add(reason);
                throw new IllegalStateException("observer failure");
            });
        security(SecurityCode.AUTHENTICATION_FAILED,()->f.initiate("missing",A,null,command()));
        assertEquals(List.of(SecurityObservation.Reason.AUTHENTICATION_FAILED),seen);
        seen.clear();security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",B,null,command()));
        assertEquals(List.of(SecurityObservation.Reason.AUTHORIZATION_DENIED),seen);
        seen.clear();storeDown=true;
        security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        assertTrue(seen.contains(SecurityObservation.Reason.GRANT_SOURCE_UNAVAILABLE));
        assertTrue(db.rows.isEmpty());
        seen.clear();storeDown=false;var view=f.initiate("alice",A,null,command()).value();
        assertTrue(seen.isEmpty());assertEquals(1,db.rows.size());
        seen.clear();storeDown=true;
        app(Code.NOT_FOUND,()->f.metadata("alice",A,null,new FileId(view.file().fileId())));
        assertEquals(List.of(SecurityObservation.Reason.GRANT_SOURCE_UNAVAILABLE),seen);
        assertEquals(1,db.rows.size());
    }

    @Test void administrativeAndIdentityAuthorityOutagesRemainDenied(){
        var seen=new ArrayList<String>();
        var sink=(SecurityObservation)(boundary,reason)->seen.add(boundary.name()+":"+reason.name());
        var clock=Clock.fixed(NOW,ZoneOffset.UTC);
        Verifier verifier=bearer->tokens.get(bearer);
        var policy=new SecuredPolicyAdmin(new Trust(ISS,AUD),verifier,
            (i,s,a,t)->Optional.of(ALICE),(actor,t)->true,(t,m)->null,
            (actor,scope,action)->true,clock,sink);
        security(SecurityCode.ACCESS_DENIED,()->policy.authorize("alice",SCOPE,null,SecuredPolicyAdmin.Action.POLICY_READ));
        assertTrue(seen.contains("POLICY_ADMIN:ENTITLEMENT_UNAVAILABLE"));
        seen.clear();
        var backend=new SecuredBackendAdmin(new Trust(ISS,AUD),verifier,
            (i,s,a)->Optional.of(ALICE),(actor,action,id)->{throw new IllegalStateException("private authority failure");},clock,sink);
        security(SecurityCode.ACCESS_DENIED,()->backend.authorize("alice",null,SecuredBackendAdmin.Action.LIST,null));
        assertTrue(seen.contains("BACKEND_ADMIN:PERMISSION_UNAVAILABLE"));
        seen.clear();
        var file=new SecuredFiles(new Trust(ISS,AUD),verifier,(i,s,a)->Optional.of(ASSET),
            (actor,t)->{throw new IllegalStateException("private membership failure");},
            (t,m)->new ModuleEntitlement(A.value(),"file-storage",true,NOW),caller->grants,
            db,clock,Duration.ofMinutes(5),sink);
        security(SecurityCode.ACCESS_DENIED,()->file.initiate("alice",A,null,command()));
        assertTrue(seen.contains("FILES:MEMBERSHIP_UNAVAILABLE"));assertTrue(db.rows.isEmpty());
        seen.clear();
        var bindingDown=new SecuredFiles(new Trust(ISS,AUD),verifier,(i,s,a)->{throw new IllegalStateException("private binding failure");},
            (actor,t)->true,(t,m)->new ModuleEntitlement(A.value(),"file-storage",true,NOW),caller->grants,
            db,clock,Duration.ofMinutes(5),sink);
        security(SecurityCode.ACCESS_DENIED,()->bindingDown.initiate("alice",A,null,command()));
        assertTrue(seen.contains("FILES:BINDING_UNAVAILABLE"));assertTrue(db.rows.isEmpty());
    }

    @Test void i01_validContextAndCorrelation(){
        var f=facade();UUID correlation=id(9);
        var a=f.initiate("alice",A,correlation,command());var b=f.initiate("alice",A,correlation,command());
        assertEquals(a.value(),b.value());assertEquals(correlation,a.correlation().correlationId());
        assertEquals(correlation,b.correlation().correlationId());assertNotEquals(a.correlation().requestId(),b.correlation().requestId());
        assertEquals(1,db.rows.size());assertEquals(new Caller(A,"actor-a","asset"),db.rows.getFirst().request().caller());
    }
    @Test void i02_badCredentialsFailBeforeWrites(){
        tokens.put("issuer",token("alice","https://other.example.test",Set.of(AUD),TokenKind.ACCESS,NOW,END));
        tokens.put("audience",token("alice",ISS,Set.of("other"),TokenKind.ACCESS,NOW,END));
        tokens.put("id-token",token("alice",ISS,Set.of(AUD),TokenKind.ID,NOW,END));
        tokens.put("expired",token("alice",ISS,Set.of(AUD),TokenKind.ACCESS,NOW.minusSeconds(1),NOW));
        tokens.put("future",token("alice",ISS,Set.of(AUD),TokenKind.ACCESS,NOW.plusSeconds(1),END));
        var f=facade();
        for(String s:List.of("missing","issuer","audience","id-token","expired","future",""))
            security(SecurityCode.AUTHENTICATION_FAILED,()->f.initiate(s,A,null,command()));
        security(SecurityCode.AUTHENTICATION_FAILED,()->f.initiate(null,A,null,command()));assertTrue(db.rows.isEmpty());
    }
    @Test void i03_bindingTenantAndAction(){
        var f=facade();security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",B,null,command()));
        security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",null,null,command()));
        bindings.remove("alice");security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        bindings.put("alice",new Binding(new ActorReference(ActorType.SERVICE,"actor-a",null,null),A,"asset",Set.of(Action.values())));
        security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        bindings.put("alice",new Binding(ALICE,A,"asset",Set.of(Action.READ_METADATA)));
        security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));assertTrue(db.rows.isEmpty());
    }
    @Test void i04_membershipAndEntitlement(){
        var f=facade();member=false;security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        member=true;enabled=false;security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        enabled=true;effective=NOW.plusSeconds(1);security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        effective=NOW;entitlementTenant=B.value();security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        entitlementTenant=A.value();module="other";security(SecurityCode.ACCESS_DENIED,()->f.initiate("alice",A,null,command()));
        assertTrue(db.rows.isEmpty());
    }
    @Test void i05_sameTenantAndCrossAppNeedGrants(){
        var f=facade();var v=f.initiate("alice",A,null,command()).value();var file=new FileId(v.file().fileId());
        grants.add(grant(SAME,Action.READ_METADATA,GrantScope.OWNED_FILE,null,NOW,END,false));
        grants.add(grant(ITSM,Action.READ_METADATA,GrantScope.OWNED_FILE,null,NOW,END,false));
        app(Code.NOT_FOUND,()->f.metadata("bob",A,null,file));app(Code.NOT_FOUND,()->f.metadata("itsm",A,null,file));
        grants.add(grant(ITSM,Action.READ_METADATA,GrantScope.EXACT_FILE,file,NOW,END,false));
        assertEquals(v.file(),f.metadata("itsm",A,null,file).value().file());
        security(SecurityCode.ACCESS_DENIED,()->f.metadata("itsm",B,null,file));
        app(Code.NOT_FOUND,()->f.session("itsm",A,null,v.session()));
        grants.add(grant(ITSM,Action.READ_SESSION,GrantScope.EXACT_FILE,file,NOW,END,false));
        assertEquals(v.session(),f.session("itsm",A,null,v.session()).value().session());
    }
    @Test void i06_expiredRevokedAndReplay(){
        var f=facade();var v=f.initiate("alice",A,null,command()).value();var file=new FileId(v.file().fileId());
        grants.removeIf(g->g.action()==Action.READ_SESSION);
        grants.add(grant(ASSET,Action.READ_SESSION,GrantScope.OWNED_FILE,null,NOW.minusSeconds(1),NOW,false));
        grants.add(grant(ASSET,Action.READ_SESSION,GrantScope.OWNED_FILE,null,NOW,END,true));
        assertEquals(v.file(),f.metadata("alice",A,null,file).value().file());
        app(Code.NOT_FOUND,()->f.session("alice",A,null,v.session()));app(Code.DENIED,()->f.initiate("alice",A,null,command()));
        assertEquals(1,db.rows.size());
    }
    @Test void i07_authorityFailureIsNeutral(){
        var f=facade();var v=f.initiate("alice",A,null,command()).value();storeDown=true;
        var failure=assertThrows(SecurityFailure.class,()->f.initiate("alice",A,null,command()));
        assertEquals(SecurityCode.ACCESS_DENIED,failure.code());assertFalse(failure.getMessage().contains("secret"));
        app(Code.NOT_FOUND,()->f.metadata("alice",A,null,new FileId(v.file().fileId())));
        assertEquals(1,db.rows.size());
    }
}
