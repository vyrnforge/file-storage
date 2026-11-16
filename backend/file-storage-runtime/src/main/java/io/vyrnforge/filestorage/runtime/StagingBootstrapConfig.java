package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.runtime.transfer.TransferLimits;
import io.vyrnforge.filestorage.security.SecuredFiles;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Strict non-secret, operator-owned staging properties. No environment fallback or unknown keys. */
public final class StagingBootstrapConfig {
    record DbRole(String login,String reference){}
    final String host,database;
    final int dbPort,serverPort,connectSeconds,socketSeconds,attempts,outboxBatch;
    final long delayMillis;
    final Path ca,secretRoot,jwks,spool;
    final SecuredFiles.Trust trust;
    final Duration sessionTtl,idempotencyLease,handleTtl,operationLease,retryDelay,keyAge,tokenLifetime;
    final TransferLimits limits;
    final boolean enabled,dispatch;
    final Map<RuntimeDatabaseRoles.Role,DbRole> roles;
    final Map<LocationId,StagingRuntimeDependencies.Pin> locations;
    private StagingBootstrapConfig(Properties p){
        if(!"staging".equals(take(p,"environment")))throw invalid();
        host=take(p,"db.host");database=take(p,"db.name");
        if(!host.matches("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}")||!database.matches("[a-z][a-z0-9_]{0,62}"))throw invalid();
        dbPort=number(p,"db.port",1,65535);serverPort=number(p,"server.port",1,65535);
        connectSeconds=number(p,"db.connect-seconds",1,30);socketSeconds=number(p,"db.socket-seconds",1,60);
        ca=path(p,"db.ca");secretRoot=path(p,"secret.root");jwks=path(p,"iam.jwks");spool=path(p,"provider.spool");
        trust=new SecuredFiles.Trust(take(p,"iam.issuer"),take(p,"iam.audience"));
        URI issuer=URI.create(trust.issuer());
        if(!"https".equals(issuer.getScheme())||issuer.getHost()==null||issuer.getUserInfo()!=null||issuer.getQuery()!=null||issuer.getFragment()!=null)throw invalid();
        keyAge=seconds(p,"iam.snapshot-seconds",1,3600);tokenLifetime=seconds(p,"iam.token-seconds",1,3600);
        sessionTtl=seconds(p,"session.seconds",1,3600);idempotencyLease=seconds(p,"idempotency.lease-seconds",1,300);
        handleTtl=seconds(p,"handle.seconds",1,3600);operationLease=seconds(p,"operation.lease-seconds",2,300);
        retryDelay=seconds(p,"retry.seconds",0,300);attempts=number(p,"attempts",1,10);outboxBatch=number(p,"outbox.batch",1,100);
        delayMillis=number(p,"worker.delay-ms",100,3600000);
        limits=new TransferLimits(number(p,"transfer.max-bytes",1,1000000000),number(p,"transfer.concurrency",1,32),
            number(p,"transfer.buffer-bytes",1024,65536),seconds(p,"transfer.timeout-seconds",1,120));
        if(operationLease.compareTo(limits.timeout())<=0||handleTtl.compareTo(sessionTtl)>0)throw invalid();
        enabled=bool(p,"staging.enabled");dispatch=bool(p,"dispatch.enabled");
        if(dispatch&&!enabled)throw invalid();
        var map=new EnumMap<RuntimeDatabaseRoles.Role,DbRole>(RuntimeDatabaseRoles.Role.class);
        var refs=new HashSet<String>();var logins=new HashSet<String>();
        for(var role:RuntimeDatabaseRoles.Role.values()){
            String login=take(p,"db."+role+".login"),ref=reference(take(p,"db."+role+".ref"));
            if(!login.matches("[a-z_][a-z0-9_]{0,62}")||!logins.add(login)||!refs.add(ref))throw invalid();
            map.put(role,new DbRole(login,ref));
        }
        roles=Map.copyOf(map);
        int count=number(p,"provider.count",1,32);
        var pins=new HashMap<LocationId,StagingRuntimeDependencies.Pin>();
        for(int i=1;i<=count;i++){
            String k="provider."+i+".";
            var provider=Provider.valueOf(take(p,k+"kind"));
            var target=new Target(provider,URI.create(take(p,k+"endpoint")),take(p,k+"region"),Addressing.valueOf(take(p,k+"addressing")));
            if(!"https".equals(target.endpoint().getScheme())||!(target.endpoint().getPath().isEmpty()||"/".equals(target.endpoint().getPath()))
                ||!target.region().matches("[A-Za-z0-9-]{1,64}")||target.endpoint().getPort()==0||target.endpoint().getPort()>65535
                ||(provider==Provider.S3&&target.addressing()!=Addressing.PATH&&target.addressing()!=Addressing.VIRTUAL_HOST)
                ||(provider==Provider.OSS&&target.addressing()!=Addressing.VIRTUAL_HOST))throw invalid();
            var id=new LocationId(UUID.fromString(take(p,k+"location")));
            var backend=new BackendId(UUID.fromString(take(p,k+"backend")));
            String bucket=take(p,k+"bucket"),prefix=take(p,k+"prefix"),ref=reference(take(p,k+"ref"));
            if(!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")||!prefix.matches("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*/")
                ||prefix.length()>512||!refs.add(ref))throw invalid();
            if(pins.put(id,new StagingRuntimeDependencies.Pin(target,new Location(id,backend,bucket,prefix),new CredentialRef(ref)))!=null)throw invalid();
        }
        locations=Map.copyOf(pins);
        if(!p.isEmpty())throw invalid();
    }
    public static StagingBootstrapConfig load(Path path){
        try{return new StagingBootstrapConfig(properties(read(path,65536)));}
        catch(IOException|RuntimeException ex){throw invalid();}
    }
    static IllegalArgumentException invalid(){return new IllegalArgumentException("invalid staging configuration");}
    static String reference(String s){
        if(!s.matches("ref:staging/file-storage/[a-z0-9][a-z0-9_-]{0,63}"))throw invalid();
        return s;
    }
    static byte[] read(Path path,int limit)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("invalid file");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=in.readNBytes(limit+1);if(bytes.length==0||bytes.length>limit)throw new IOException("invalid file");
            return bytes;
        }
    }
    static Properties properties(byte[] bytes)throws IOException{
        var p=new Properties(){
            @Override public synchronized Object put(Object k,Object v){if(containsKey(k))throw invalid();return super.put(k,v);}
        };
        try(var reader=new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8.newDecoder())){p.load(reader);}
        return p;
    }
    static String take(Properties p,String key){
        Object value=p.remove(key);
        if(!(value instanceof String s)||s.isBlank()||!s.equals(s.strip())||s.length()>4096||s.codePoints().anyMatch(Character::isISOControl))throw invalid();
        return s;
    }
    private static int number(Properties p,String key,int min,int max){
        String value=take(p,key);if(!value.matches("0|[1-9][0-9]{0,9}"))throw invalid();
        int n=Integer.parseInt(value);if(n<min||n>max)throw invalid();return n;
    }
    private static Duration seconds(Properties p,String key,int min,int max){return Duration.ofSeconds(number(p,key,min,max));}
    private static boolean bool(Properties p,String key){
        return switch(take(p,key)){case "true"->true;case "false"->false;default->throw invalid();};
    }
    private static Path path(Properties p,String key){
        Path path=Path.of(take(p,key));if(!path.isAbsolute()||!path.equals(path.normalize()))throw invalid();return path;
    }
    @Override public String toString(){return "StagingBootstrapConfig[redacted]";}
}
