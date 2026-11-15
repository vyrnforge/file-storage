package io.vyrnforge.filestorage.domain;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.file.FileReference;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageModelTest {
    static final TenantId T=new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000001"));
    static final TenantId OTHER=new TenantId(UUID.fromString("01900000-0000-7000-8000-000000000002"));
    static UUID id(int n){return new UUID(0,n);}
    static final BackendId BO=new BackendId(id(1)), BS=new BackendId(id(2));
    static final LocationId LO=new LocationId(id(3)), LS=new LocationId(id(4));
    static final Location OSS=new Location(LO,BO,"oss-bucket","files/"), S3=new Location(LS,BS,"s3-bucket","files/");
    static final Target OT=new Target(Provider.OSS,URI.create("https://oss.example.test"),"region",Addressing.AUTO);
    static final Target ST=new Target(Provider.S3,URI.create("https://s3.example.test"),"region",Addressing.PATH);
    static final CredentialRef REF=new CredentialRef("ref:synthetic/test");
    static final Backend O=Backend.draft(BO,OT,REF).validate(1).activate(), S=Backend.draft(BS,ST,REF).validate(1).activate();
    static final Scope SCOPE=new Scope(T,"asset","attachment");
    static final Map<LocationId,Location> LOCS=Map.of(LO,OSS,LS,S3);
    static final Map<BackendId,Backend> BACKS=Map.of(BO,O,BS,S);
    static final Set<LocationId> ALLOWED=Set.of(LO,LS);
    static final File FILE=File.create(new FileId("legacy:opaque-42"),T,"example");
    static final VersionId V=new VersionId(id(5));
    static final Locator OBJECT=new Locator(LO,"files/synthetic",null);
    static final Content CONTENT=new Content(5,"a".repeat(64),"text/plain");
    static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z"), END=NOW.plusSeconds(60);
    static Policy policy(long r,LocationId l){return new Policy(r,List.of(new Rule(SCOPE,l,0)));}
    static Placement placement(Policy p){return StorageModel.select(SCOPE,p,LOCS,BACKS,ALLOWED);}
    static Version available(){return Version.pending(FILE,V,OBJECT).verify().finish(CONTENT,Scan.CLEAN);}
    static UploadSession session(Placement p){return UploadSession.initiate(new SessionId(id(6)),FILE,V,SCOPE,p,OBJECT,END,NOW);}

    @Test void a01_migrationPreservesLogicalIdentity(){
        var original=available();var migrated=original.migrate(new Locator(LS,"new-key","generation"),CONTENT);
        assertEquals(original.fileId(),migrated.fileId());assertEquals(original.id(),migrated.id());
        assertEquals(LS,migrated.locator().location());assertEquals(LO,original.locator().location());
        assertEquals(FILE.reference(),new FileReference("legacy:opaque-42",T.value(),"file-storage"));
        assertThrows(IllegalArgumentException.class,()->original.migrate(new Locator(LS,"new-key",null),new Content(6,"a".repeat(64),"text/plain")));
    }
    @Test void a02_policyChangesOnlyNewPlacement(){
        var p=placement(policy(1,LO));var session=session(p);
        assertEquals(LS,placement(policy(2,LS)).location());assertEquals(LO,session.placement().location());
        assertEquals(1,session.placement().policyRevision());assertEquals(1,session.placement().configRevision());
        assertEquals(LO,available().locator().location());
        assertEquals(UploadState.COMPLETED,session.start(NOW).verify(NOW).complete(available(),NOW).state());
    }
    @Test void a03_failClosed(){
        assertThrows(IllegalStateException.class,()->StorageModel.select(SCOPE,policy(1,LO),LOCS,BACKS,Set.of(LS)));
        for (Backend b:List.of(Backend.draft(BO,OT,REF),O.drain(),O.drain().retire(0,0,0)))
            assertThrows(IllegalStateException.class,()->StorageModel.select(SCOPE,policy(1,LO),LOCS,Map.of(BO,b),ALLOWED));
        var ambiguous=new Policy(1,List.of(new Rule(SCOPE,LO,0),new Rule(SCOPE,LS,0)));
        assertThrows(IllegalStateException.class,()->placement(ambiguous));
        assertThrows(IllegalStateException.class,()->placement(new Policy(1,List.of())));
        var ordered=new Policy(1,List.of(new Rule(SCOPE,LS,1),new Rule(SCOPE,LO,0)));
        assertEquals(LO,placement(ordered).location());
    }
    @Test void a04_drainingAndRetirement(){
        var draining=O.drain();assertTrue(draining.permitsExistingAccess());
        assertThrows(IllegalStateException.class,()->draining.retire(1,0,0));
        assertThrows(IllegalStateException.class,()->draining.retire(0,1,0));
        assertThrows(IllegalStateException.class,()->draining.retire(0,0,1));
        assertFalse(draining.retire(0,0,0).permitsExistingAccess());
        assertEquals(UploadState.COMPLETED,session(placement(policy(1,LO))).start(NOW).verify(NOW).complete(available(),NOW).state());
    }
    @Test void a05_tenantBoundScope(){
        var other=new Scope(OTHER,"asset","attachment");
        assertThrows(IllegalStateException.class,()->StorageModel.select(other,policy(1,LO),LOCS,BACKS,ALLOWED));
        assertThrows(IllegalArgumentException.class,()->new TenantId(id(9)));
        var forged=new Placement(other,LO,1,1);
        assertThrows(IllegalArgumentException.class,()->session(forged));
        assertFalse(available().availableTo(File.create(FILE.id(),OTHER,"same-id")));
    }
    @Test void a06_lifecycleAndEvidence(){
        var pending=Version.pending(FILE,V,OBJECT);
        assertThrows(IllegalStateException.class,()->pending.finish(CONTENT,Scan.CLEAN));
        assertThrows(IllegalArgumentException.class,()->new Content(-1,"a".repeat(64),"text/plain"));
        assertThrows(IllegalArgumentException.class,()->new Content(1,"bad","text/plain"));
        assertFalse(pending.verify().finish(CONTENT,Scan.MALICIOUS).availableTo(FILE));
        assertFalse(pending.verify().finish(CONTENT,Scan.UNAVAILABLE).availableTo(FILE));
        assertTrue(available().availableTo(FILE));assertFalse(available().availableTo(FILE.softDelete()));
        assertEquals(FileState.ACTIVE,FILE.softDelete().restore().state());
        assertThrows(IllegalStateException.class,()->FILE.softDelete().requestPurge(true,true));
        assertThrows(IllegalStateException.class,()->FILE.softDelete().requestPurge(false,false));
        assertThrows(IllegalStateException.class,()->FILE.softDelete().requestPurge(true,false).confirmPurge(false));
        assertEquals(FileState.PURGED,FILE.softDelete().requestPurge(true,false).confirmPurge(true).state());
        var session=session(placement(policy(1,LO)));assertThrows(IllegalStateException.class,()->session.start(END));
        assertEquals(UploadState.EXPIRED,session.expire(END).state());
        assertThrows(IllegalStateException.class,()->session.cancel().start(NOW));
        var verifying=session.start(NOW).verify(NOW);
        var wrong=Version.pending(FILE,V,new Locator(LO,"other-key",null)).verify().finish(CONTENT,Scan.CLEAN);
        assertThrows(IllegalArgumentException.class,()->verifying.complete(wrong,NOW));
    }
    @Test void a07_revisionAndPhysicalTarget(){
        var candidate=O.reviseCredential(new CredentialRef("ref:synthetic/next"));
        assertEquals(2,candidate.configRevision());assertNull(candidate.validatedRevision());
        assertThrows(IllegalStateException.class,candidate::activate);assertThrows(IllegalStateException.class,()->candidate.validate(1));
        O.requireSamePhysicalTarget(candidate.validate(2).activate());
        assertThrows(IllegalArgumentException.class,()->O.requireSamePhysicalTarget(Backend.draft(BO,ST,REF)));
        assertThrows(IllegalArgumentException.class,()->OSS.requireSamePhysicalLocation(new Location(LO,BO,"changed","files/")));
        assertThrows(IllegalArgumentException.class,()->new CredentialRef("raw-credential"));
        assertFalse(REF.toString().contains("synthetic"));
    }
    @Test void a09_kernelReferenceActualArtifact(){
        assertThrows(IllegalArgumentException.class, () -> new FileId("x".repeat(513)));
        assertEquals("x".repeat(512), File.create(new FileId("x".repeat(512)), T, "file").reference().fileId());
        FileReference ref=FILE.reference();assertEquals(T.value(),ref.ownerTenantId());assertEquals("file-storage",ref.storageService());
        assertEquals("legacy:opaque-42",ref.fileId());assertNull(ref.name());assertNull(ref.mimeType());assertNull(ref.size());
        assertEquals(Set.of("fileId","ownerTenantId","storageService","name","mimeType","size","createdAt"),
            Arrays.stream(FileReference.class.getRecordComponents()).map(c->c.getName()).collect(java.util.stream.Collectors.toSet()));
    }
}
