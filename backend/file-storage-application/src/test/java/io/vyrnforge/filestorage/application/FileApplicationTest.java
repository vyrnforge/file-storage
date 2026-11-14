package io.vyrnforge.filestorage.application;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class FileApplicationTest {
    static UUID id(long n){return new UUID(0x0190000000007000L,0x8000000000000000L+n);}
    static final TenantId A=new TenantId(id(1)), B=new TenantId(id(2));
    static final Caller ALICE=new Caller(A,"alice","asset"), BOB=new Caller(B,"bob","asset"), ITSM=new Caller(A,"alice","itsm");
    static final BackendId O=new BackendId(id(3)), S=new BackendId(id(4));
    static final LocationId OL=new LocationId(id(5)), SL=new LocationId(id(6));
    static final Location OSS=new Location(OL,O,"oss-test","objects/"), S3=new Location(SL,S,"s3-test","objects/");
    static final Scope SCOPE=new Scope(A,"asset","attachment");
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    static Backend backend(BackendId id,Provider provider){
        return Backend.draft(id,new Target(provider,URI.create("https://storage.example.test"),"region",Addressing.AUTO),
                new CredentialRef("ref:synthetic")).validate(1).activate();
    }
    static Configuration config(long rev,LocationId loc){
        return new Configuration(new Policy(rev,List.of(new Rule(SCOPE,loc,0))),
            Map.of(OL,OSS,SL,S3),Map.of(O,backend(O,Provider.OSS),S,backend(S,Provider.S3)),Set.of(OL,SL));
    }
    static Initiate command(String key){return new Initiate("attachment","sample.txt",5,"text/plain",key);}
    static final class MutableClock extends Clock {
        Instant now=NOW;
        public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId zone){return Clock.fixed(now,zone);}
        public Instant instant(){return now;}
    }
    static final class Grants implements Authorization {
        boolean allowed=true; boolean sessionAllowed=true;
        public boolean mayInitiate(Caller caller,String purpose){return allowed&&caller.equals(ALICE)&&purpose.equals("attachment");}
        public boolean mayAccess(Caller caller,Action action,Resource r){
            return allowed&&(action!=Action.READ_SESSION||sessionAllowed)&&caller.equals(ALICE)&&r.tenant().equals(A)&&r.ownerConsumer().equals("asset");
        }
    }
    /** Copy-on-write unit of work simulates atomic rollback; no production persistence claim. */
    static final class Memory implements Transactions {
        List<Entry> rows=new ArrayList<>();
        Configuration config=config(1,OL);
        boolean failInsert;
        public synchronized <T>T execute(Function<Unit,T> work){
            var staged=new ArrayList<>(rows);
            Unit unit=new Unit(){
                public Optional<Configuration> configuration(Scope scope){return Optional.ofNullable(config);}
                public Optional<Entry> byRequest(RequestIdentity key){return staged.stream().filter(e->e.request().equals(key)).findFirst();}
                public Optional<Entry> byFile(TenantId t,FileId f){return staged.stream().filter(e->e.file().tenant().equals(t)&&e.file().id().equals(f)).findFirst();}
                public Optional<Entry> bySession(TenantId t,SessionId s){return staged.stream().filter(e->e.file().tenant().equals(t)&&e.session().id().equals(s)).findFirst();}
                public void insert(Entry e){
                    if(staged.stream().anyMatch(x->x.request().equals(e.request())||x.file().id().equals(e.file().id())||x.version().id().equals(e.version().id())||x.session().id().equals(e.session().id())))throw new Failure(Code.CONFLICT);
                    staged.add(e);
                    if(failInsert)throw new Failure(Code.CONFLICT);
                }
            };
            T result=work.apply(unit);
            rows=staged;return result;
        }
    }
    final Memory db=new Memory();final Grants grants=new Grants();final MutableClock clock=new MutableClock();
    final AtomicLong next=new AtomicLong(100);
    final FileApplication app=new FileApplication(db,grants,clock,Duration.ofMinutes(5),()->id(next.incrementAndGet()));
    void code(Code expected,org.junit.jupiter.api.function.Executable call){assertEquals(expected,assertThrows(Failure.class,call).code());}
    @Test void b01_authorizedInitiation(){
        View v=app.initiate(ALICE,command("a"));
        assertEquals(A.value(),v.file().ownerTenantId());assertEquals("file-storage",v.file().storageService());
        assertEquals(VersionState.PENDING,v.versionState());assertEquals(UploadState.INITIATED,v.uploadState());
        assertEquals(1,db.rows.size());Entry e=db.rows.getFirst();
        assertEquals(OL,e.session().placement().location());assertEquals(1,e.session().placement().policyRevision());
        assertEquals(e.version().locator(),e.session().expectedObject());assertEquals(v.file().fileId(),e.file().id().value());
        var metadata=app.metadata(ALICE,e.file().id());assertEquals(v.file(),metadata.file());assertEquals("sample.txt",metadata.name());assertEquals(5,metadata.declaredSize());assertEquals(v,app.session(ALICE,v.session()));
        assertFalse(v.toString().contains("objects/"));assertFalse(v.toString().contains("oss-test"));
    }
    @Test void b02_policySwitchPreservesExisting(){
        var old=app.initiate(ALICE,command("a"));db.config=config(2,SL);
        var newer=app.initiate(ALICE,command("b"));
        assertEquals(OL,db.rows.get(0).session().placement().location());
        assertEquals(SL,db.rows.get(1).session().placement().location());
        assertEquals(2,db.rows.get(1).session().placement().policyRevision());
        assertEquals(old,app.session(ALICE,old.session()));assertNotEquals(old.file(),newer.file());
    }
    @Test void b03_invalidPlacementNoMutation(){
        db.config=null;code(Code.NO_PLACEMENT,()->app.initiate(ALICE,command("a")));
        var c=config(1,OL);
        db.config=new Configuration(new Policy(1,List.of(new Rule(SCOPE,OL,0),new Rule(SCOPE,SL,0))),c.locations(),c.backends(),c.eligible());
        code(Code.NO_PLACEMENT,()->app.initiate(ALICE,command("a")));
        db.config=new Configuration(c.policy(),c.locations(),c.backends(),Set.of(SL));
        code(Code.NO_PLACEMENT,()->app.initiate(ALICE,command("a")));
        db.config=new Configuration(c.policy(),c.locations(),Map.of(O,backend(O,Provider.OSS).drain()),c.eligible());
        code(Code.NO_PLACEMENT,()->app.initiate(ALICE,command("a")));assertTrue(db.rows.isEmpty());
    }
    @Test void b04_deniedAndTenantScope(){
        var v=app.initiate(ALICE,command("a"));FileId f=new FileId(v.file().fileId());
        for(Caller caller:List.of(BOB,ITSM,new Caller(A,"eve","asset"))){
            code(Code.NOT_FOUND,()->app.metadata(caller,f));code(Code.NOT_FOUND,()->app.session(caller,v.session()));
            code(Code.DENIED,()->app.initiate(caller,command("b")));
        }
        code(Code.DENIED,()->app.initiate(null,command("b")));assertEquals(1,db.rows.size());
    }
    @Test void metadataPermissionDoesNotGrantSessionAccess(){
        var v=app.initiate(ALICE,command("a"));grants.sessionAllowed=false;
        var metadata=app.metadata(ALICE,new FileId(v.file().fileId()));assertEquals(v.file(),metadata.file());
        assertFalse(Arrays.stream(MetadataView.class.getRecordComponents()).anyMatch(c->c.getName().equals("session")));
        code(Code.NOT_FOUND,()->app.session(ALICE,v.session()));code(Code.DENIED,()->app.initiate(ALICE,command("a")));
    }
    @Test void b05_replayReauthorizesAndConflicts(){
        var first=app.initiate(ALICE,command("a"));db.config=config(2,SL);
        assertEquals(first,app.initiate(ALICE,command("a")));assertEquals(1,db.rows.size());
        code(Code.CONFLICT,()->app.initiate(ALICE,new Initiate("attachment","different.txt",5,"text/plain","a")));
        grants.allowed=false;code(Code.DENIED,()->app.initiate(ALICE,command("a")));
        assertEquals(1,db.rows.size());
    }
    @Test void b06_expiryBoundary(){
        var first=app.initiate(ALICE,command("a"));clock.now=first.expiresAt().minusNanos(1);
        assertEquals(UploadState.INITIATED,app.session(ALICE,first.session()).uploadState());
        clock.now=first.expiresAt();assertEquals(UploadState.EXPIRED,app.session(ALICE,first.session()).uploadState());
        assertEquals(UploadState.EXPIRED,app.initiate(ALICE,command("a")).uploadState());
        assertEquals(UploadState.INITIATED,db.rows.getFirst().session().state());assertEquals(1,db.rows.size());
    }
    @Test void b07_atomicFailureAndRetry(){
        db.failInsert=true;code(Code.CONFLICT,()->app.initiate(ALICE,command("a")));assertTrue(db.rows.isEmpty());
        db.failInsert=false;assertNotNull(app.initiate(ALICE,command("a")));assertEquals(1,db.rows.size());
    }
}
