package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.persistence.*;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.filestorage.spi.StorageProvider;
import io.vyrnforge.platform.kernel.persistence.jdbc.JdbcOutboxPublisher;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.scheduling.annotation.Scheduled;

/** One explicit operational composition; no physical deletion or migration worker. */
public final class StagingOperations {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(StagingOperations.class);
    private final ApiRuntimeDependencies api;
    private final StagingRuntimeDependencies config;
    private final boolean dispatchEnabled;
    private final AtomicBoolean running=new AtomicBoolean();
    private final UploadVerificationWorker verify;
    private final UploadScanWorker scan;
    private final JdbcUploadRecovery recovery;
    private final JdbcOutboxPublisher publisher;
    private final CompletionCommands commands;
    private final DurableDownloadAuthority downloads;
    private final PrivateTransferGateway gateway;

    StagingOperations(ApiRuntimeDependencies api,StagingRuntimeDependencies config,SecuredFiles secured,boolean dispatchEnabled){
        this.api=Objects.requireNonNull(api);this.config=Objects.requireNonNull(config);this.dispatchEnabled=dispatchEnabled;
        var roles=Objects.requireNonNull(api.databaseRoles(),"separated database roles required");
        if(!api.trust().equals(config.trust())||!api.backend().approvedTargets().equals(
                config.locations().values().stream().map(StagingRuntimeDependencies.Pin::target).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("staging authority configuration mismatch");
        if(!ready())throw new IllegalStateException("staging dependency admission failed");
        var clock=api.clock();
        var transfers=RuntimeComposition.uploadTransfers(roles,clock,config.handleTtl(),5);
        downloads=new DurableDownloadAuthority(secured,RuntimeComposition.downloadTransfers(roles,clock,config.handleTtl(),5));
        var upload=new DurableUploadAuthority(secured,transfers);
        var workerSource=roles.source(RuntimeDatabaseRoles.Role.WORKER);
        var workerTransfers=new JdbcUploadTransfers(workerSource,clock,config.handleTtl(),5);
        var operations=new JdbcOperations(workerSource,clock,config.operationLease(),config.retryDelay(),config.attempts());
        var scans=new JdbcUploadScan(workerTransfers,operations,config.worker(),config.attempts());
        var work=new JdbcCompletionWork(workerTransfers,operations,scans,config.attempts());
        commands=new CompletionCommands(secured,transfers,work);
        var transferResolver=resolver(new JdbcRegistry(roles.source(RuntimeDatabaseRoles.Role.TRANSFER),clock),config,true);
        var workerResolver=resolver(new JdbcRegistry(workerSource,clock),config,false);
        gateway=new PrivateTransferGateway(DurableDownloadAuthority.combine(upload,downloads),transferResolver,config.limits(),clock);
        verify=new UploadVerificationWorker(work,workerResolver,config.limits(),clock);
        scan=new UploadScanWorker(scans,workerResolver,config.scanner(),config.limits(),clock);
        recovery=new JdbcUploadRecovery(workerTransfers,config.worker());
        publisher=JdbcEventDispatcher.create(roles.source(RuntimeDatabaseRoles.Role.DISPATCHER),config.transport(),clock,
            config.attempts(),config.retryDelay(),config.operationLease());
    }
    public boolean ready(){
        try{return api.databaseRoles()!=null&&api.readiness().ready()&&config.readiness().ready()&&api.databaseRoles().ready();}
        catch(RuntimeException ex){return false;}
    }
    CompletionCommands commands(){return commands;}
    DurableDownloadAuthority downloads(){return downloads;}
    PrivateTransferGateway gateway(){return gateway;}
    /** Fixed delay, one attempt per handler, bounded outbox batch, no overlap in this process.
     * Provider/transport implementations must enforce hard external deadlines; leases fence commits.
     */
    @Scheduled(fixedDelayString="${fs.staging.delay-ms}",initialDelayString="${fs.staging.delay-ms}")
    public void tick(){
        if(!running.compareAndSet(false,true))return;
        try{
            if(!ready())return;
            attempt(()->recovery.runOne(),"recovery");
            attempt(()->verify.runOne(),"verification");
            attempt(()->scan.runOne(),"scan");
            if(dispatchEnabled)attempt(()->publisher.publishBatch(config.outboxBatch()),"dispatch");
        }finally{running.set(false);}
    }
    private static void attempt(Runnable work,String duty){
        try{work.run();}catch(RuntimeException ex){LOG.warn("Staging {} tick failed; durable recovery applies",duty);}
    }
    static TransferProviderResolver resolver(JdbcRegistry registry,StagingRuntimeDependencies config,boolean writable){
        return instruction->{
            var key=new StagingRuntimeDependencies.PinKey(instruction.backend(),instruction.revision(),instruction.locator().location());
            var pin=config.locations().get(key);
            if(pin==null||!config.readiness().ready())
                throw new TransferFailure(TransferFailure.Code.DENIED);
            var resolver=new RegistryTransferProviderResolver<AutoCloseable>(registry,ref->{
                if(!pin.credential().equals(ref))throw new TransferFailure(TransferFailure.Code.DENIED);
                return config.credentials().resolve(ref);
            },Set.of(pin.target()),Map.of(pin.target().provider(),(target,location,revision,credential)->{
                if(!pin.location().equals(location)||revision!=key.revision())throw new TransferFailure(TransferFailure.Code.DENIED);
                var lease=config.factories().get(target.provider()).open(target,location,revision,credential);
                return new TransferProviderResolver.Lease(){
                    @Override public StorageProvider provider(){return restricted(lease.provider(),instruction.locator(),writable&&instruction.action()==TransferInstruction.Action.UPLOAD);}
                    @Override public void close(){lease.close();}
                };
            }), (target,location,reference)->pin.target().equals(target)&&pin.location().equals(location)
                    &&pin.credential().equals(reference)&&within(location.prefix(),instruction.locator().objectKey()));
            return resolver.resolve(instruction);
        };
    }
    private static boolean within(String prefix,String key){
        String root=prefix.endsWith("/")?prefix:prefix+"/";
        return key.startsWith(root)&&key.length()>root.length();
    }
    private static StorageProvider restricted(StorageProvider delegate,Locator pin,boolean writable){
        return new StorageProvider(){
            private void exact(Locator object){if(!pin.equals(object))throw new StorageFailure(ErrorCode.FORBIDDEN);}
            @Override public Set<Capability> capabilities(){return Set.of();}
            @Override public Metadata put(Locator object,InputStream bytes,long size,Call call){
                exact(object);if(!writable)throw new StorageFailure(ErrorCode.FORBIDDEN);return delegate.put(object,bytes,size,call);}
            @Override public InputStream get(Locator object,Call call){exact(object);return delegate.get(object,call);}
            @Override public Metadata head(Locator object,Call call){exact(object);return delegate.head(object,call);}
            @Override public void delete(Locator object,Call call){throw new StorageFailure(ErrorCode.FORBIDDEN);}
            @Override public Page list(LocationId location,String prefix,String cursor,int limit,Call call){throw new StorageFailure(ErrorCode.FORBIDDEN);}
        };
    }
}
