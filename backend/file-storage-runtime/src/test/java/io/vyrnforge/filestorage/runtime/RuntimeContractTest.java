package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.FileApplication.*;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.security.SecuredFiles.*;
import io.vyrnforge.platform.kernel.context.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeContractTest {
    static UUID id(long n) { return new UUID(0x0190000000007000L, 0x8000000000000000L+n); }
    static final TenantId A = new TenantId(id(1)), B = new TenantId(id(2));
    static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    static final String ISS = "https://iam.example.test", AUD = "file-storage-test";
    static final ActorReference ACTOR = new ActorReference(ActorType.USER,"synthetic-actor",null,null);
    static final Scope SCOPE = new Scope(A,"asset","attachment");
    static final BackendId BACK = new BackendId(id(3));
    static final LocationId LOC = new LocationId(id(4));
    static final io.vyrnforge.filestorage.application.FileApplication.Configuration PLACEMENT =
        new io.vyrnforge.filestorage.application.FileApplication.Configuration(
            new Policy(1,List.of(new Rule(SCOPE,LOC,0))),
            Map.of(LOC,new Location(LOC,BACK,"private-bucket","private-prefix/")),
            Map.of(BACK,Backend.draft(BACK,new Target(Provider.OSS,URI.create("https://oss.example.test"),"region",Addressing.AUTO),
                    new CredentialRef("ref:synthetic")).validate(1).activate()),Set.of(LOC));
    static final String BODY = """
        {"purpose":"attachment","name":"sample.txt","expectedSize":5,"mimeType":"text/plain"}
        """;

    static final class Memory implements Transactions {
        final List<Entry> rows = new ArrayList<>();
        public synchronized <T> T execute(Function<Unit,T> work) {
            var staged = new ArrayList<>(rows);
            T result = work.apply(new Unit() {
                public Optional<io.vyrnforge.filestorage.application.FileApplication.Configuration> configuration(Scope s) {
                    return SCOPE.equals(s) ? Optional.of(PLACEMENT) : Optional.empty();
                }
                public Optional<Entry> byRequest(RequestIdentity r) { return staged.stream().filter(e->e.request().equals(r)).findFirst(); }
                public Optional<Entry> byFile(TenantId t,FileId f) { return staged.stream().filter(e->e.file().tenant().equals(t)&&e.file().id().equals(f)).findFirst(); }
                public Optional<Entry> bySession(TenantId t,SessionId s) { return staged.stream().filter(e->e.file().tenant().equals(t)&&e.session().id().equals(s)).findFirst(); }
                public void insert(Entry e) { staged.add(e); }
            });
            rows.clear(); rows.addAll(staged); return result;
        }
    }

    @Configuration(proxyBeanMethods=false)
    @Import(FileRoutes.class)
    static class TestRoutes {
        @Bean Memory memory() { return new Memory(); }
        @Bean SecuredFiles secured(Memory db) {
            return new SecuredFiles(new Trust(ISS,AUD), bearer -> {
                if (!"fixture-access".equals(bearer)) throw new IllegalArgumentException("private error detail");
                return new VerifiedAccess(ISS,"fixture-sub",Set.of(AUD),TokenKind.ACCESS,NOW,NOW.plusSeconds(300),NOW);
            }, (issuer,subject,aud)->Optional.of(new Binding(ACTOR,A,"asset",Set.of(Action.values()))),
            (actor,t)->true, (t,m)->new ModuleEntitlement(A.value(),"file-storage",true,NOW),
            caller->List.of(grant(Action.INITIATE,GrantScope.INITIATE_PURPOSE),
                grant(Action.READ_SESSION,GrantScope.OWNED_FILE),grant(Action.READ_METADATA,GrantScope.OWNED_FILE)),
            db,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofMinutes(5));
        }
        private static Grant grant(Action action,GrantScope scope) {
            return new Grant(A,ACTOR.actorId(),"asset",action,"attachment",scope,null,NOW,NOW.plusSeconds(300),false);
        }
    }

    private static HttpResponse<String> call(ConfigurableApplicationContext ctx,String method,String path,
            String bearer,TenantId tenant,String key,String body,UUID corr) throws Exception {
        var port = ctx.getEnvironment().getProperty("local.server.port");
        var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) req.header("Authorization","Bearer "+bearer);
        if (tenant != null) req.header("X-Tenant-Id",tenant.value().toString());
        if (key != null) req.header("Idempotency-Key",key);
        if (corr != null) req.header("X-Correlation-Id",corr.toString());
        if (body != null) req.header("Content-Type","application/json");
        req.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(req.build(),HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String field(String json,String name) {
        var m = Pattern.compile("\""+name+"\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(m.find(),json); return m.group(1);
    }

    @Test void defaultExecutableIsHealthOnlyAndNotReady() throws Exception {
        try (var ctx = RuntimeApplication.start("--server.port=0","--fs.role=TRANSFER")) {
            var live = call(ctx,"GET","/health/live",null,null,null,null,null);
            assertEquals(200,live.statusCode()); assertTrue(live.body().contains("TRANSFER"));
            assertEquals(503,call(ctx,"GET","/health/ready",null,null,null,null,null).statusCode());
            assertEquals(404,call(ctx,"POST","/v1/uploads","fixture-access",A,"key",BODY,null).statusCode());
            assertFalse(ctx.containsBean("secured"));
        }
    }

    @Test void startupRejectsPublicBindAndUnknownRole() {
        assertThrows(IllegalStateException.class,()->RuntimeApplication.start("--server.port=0","--server.address=0.0.0.0"));
        assertThrows(IllegalStateException.class,()->RuntimeApplication.start("--server.port=0","--fs.role=UNKNOWN"));
    }

    @Test void isolatedRoutesUseSecuredFacadeAndSafeWireProjection() throws Exception {
        try (var ctx = RuntimeApplication.application(TestRoutes.class).run("--server.port=0")) {
            UUID corr = id(9);
            var first = call(ctx,"POST","/v1/uploads","fixture-access",A,"key",BODY,corr);
            assertEquals(200,first.statusCode(),first.body());
            assertEquals(corr.toString(),first.headers().firstValue("X-Correlation-Id").orElseThrow());
            String session = field(first.body(),"sessionId"), file = field(first.body(),"fileId");
            for (String forbidden : List.of("private-bucket","private-prefix","oss.example.test","ref:synthetic","fixture-access","objectKey","backendId","locationId"))
                assertFalse(first.body().contains(forbidden),first.body());
            var replay = call(ctx,"POST","/v1/uploads","fixture-access",A,"key",BODY,corr);
            assertEquals(200,replay.statusCode()); assertEquals(session,field(replay.body(),"sessionId"));
            assertNotEquals(first.headers().firstValue("X-Request-Id"),replay.headers().firstValue("X-Request-Id"));
            assertEquals(1,ctx.getBean(Memory.class).rows.size());
            var metadata = call(ctx,"GET","/v1/files/"+file,"fixture-access",A,null,null,null);
            assertEquals(200,metadata.statusCode()); assertFalse(metadata.body().contains("sessionId"));
            assertEquals(200,call(ctx,"GET","/v1/uploads/"+session,"fixture-access",A,null,null,null).statusCode());
            assertEquals(409,call(ctx,"POST","/v1/uploads","fixture-access",A,"key",BODY.replace("sample.txt","changed.txt"),null).statusCode());
        }
    }

    @Test void isolatedRoutesDenyInvalidIdentityTenantAndInputWithoutWrites() throws Exception {
        try (var ctx = RuntimeApplication.application(TestRoutes.class).run("--server.port=0")) {
            var unauthorized = call(ctx,"POST","/v1/uploads","wrong",A,"key",BODY,id(10));
            assertEquals(401,unauthorized.statusCode(),unauthorized.body());
            assertEquals("AUTHENTICATION_FAILED",field(unauthorized.body(),"code"));
            assertFalse(unauthorized.body().contains("private error detail"));
            assertEquals(403,call(ctx,"POST","/v1/uploads","fixture-access",B,"key",BODY,null).statusCode());
            assertEquals(400,call(ctx,"POST","/v1/uploads","fixture-access",A,null,BODY,null).statusCode());
            assertEquals(400,call(ctx,"POST","/v1/uploads","fixture-access",A,"key","{broken",null).statusCode());
            assertEquals(400,call(ctx,"POST","/v1/uploads","fixture-access",A,"key",BODY.replace("5","-1"),null).statusCode());
            assertEquals(404,call(ctx,"GET","/v1/files/unknown","fixture-access",A,null,null,null).statusCode());
            assertEquals(400,call(ctx,"GET","/v1/uploads/not-a-uuid","fixture-access",A,null,null,null).statusCode());
            assertTrue(ctx.getBean(Memory.class).rows.isEmpty());
        }
    }
}
