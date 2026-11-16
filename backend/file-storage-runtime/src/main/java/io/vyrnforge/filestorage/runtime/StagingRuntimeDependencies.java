package io.vyrnforge.filestorage.runtime;

import io.vyrnforge.filestorage.application.CredentialResolver;
import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.filestorage.runtime.transfer.*;
import io.vyrnforge.filestorage.security.SecuredFiles;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.persistence.jdbc.EventTransport;
import java.time.Duration;
import java.util.*;

/** Trusted staging-only configuration; no default credentials, scanner or transport. */
public record StagingRuntimeDependencies(String environment,SecuredFiles.Trust trust,
        Map<PinKey,Pin> locations,CredentialResolver<AutoCloseable> credentials,
        Map<Provider,RegistryTransferProviderResolver.AdapterFactory<AutoCloseable>> factories,
        ContentScanner scanner,EventTransport transport,ActorReference worker,
        RuntimeReadiness.AuthorityProbe readiness,TransferLimits limits,Duration handleTtl,
        Duration operationLease,Duration retryDelay,int attempts,int outboxBatch){
    /** Internal explicit admission key; never infer a revision from the active pointer. */
    public record PinKey(BackendId backend,long revision,LocationId location){
        public PinKey{
            Objects.requireNonNull(backend);Objects.requireNonNull(location);
            if(revision<1)throw new IllegalArgumentException("invalid staging revision");
        }
        @Override public String toString(){return "PinKey[redacted]";}
    }
    public record Pin(Target target,Location location,CredentialRef credential){
        public Pin{Objects.requireNonNull(target);Objects.requireNonNull(location);Objects.requireNonNull(credential);
            if(!"https".equals(target.endpoint().getScheme())||location.prefix().isBlank()
                ||(target.provider()==Provider.OSS&&target.addressing()!=Addressing.VIRTUAL_HOST))throw new IllegalArgumentException("invalid staging pin");}
        @Override public String toString(){return "Pin[redacted]";}
    }
    public StagingRuntimeDependencies{
        if(!"staging".equals(environment))throw new IllegalArgumentException("staging environment required");
        Objects.requireNonNull(trust);Objects.requireNonNull(credentials);Objects.requireNonNull(scanner);
        Objects.requireNonNull(transport);Objects.requireNonNull(worker);Objects.requireNonNull(readiness);
        Objects.requireNonNull(limits);Objects.requireNonNull(handleTtl);Objects.requireNonNull(operationLease);Objects.requireNonNull(retryDelay);
        locations=Map.copyOf(locations);factories=Map.copyOf(factories);
        validatePins(locations);
        for(var pin:locations.values())if(!factories.containsKey(pin.target().provider()))
            throw new IllegalArgumentException("invalid staging locations");
        if(handleTtl.isZero()||handleTtl.isNegative()||handleTtl.compareTo(Duration.ofHours(1))>0
            ||operationLease.compareTo(limits.timeout())<=0||operationLease.compareTo(Duration.ofMinutes(5))>0
            ||retryDelay.isNegative()||retryDelay.compareTo(Duration.ofMinutes(5))>0||attempts<1||attempts>10
            ||outboxBatch<1||outboxBatch>100)throw new IllegalArgumentException("invalid staging bounds");
    }
    static void validatePins(Map<PinKey,Pin> pins){
        if(pins.isEmpty()||pins.size()>32)throw new IllegalArgumentException("invalid staging locations");
        var locations=new HashMap<LocationId,Location>();
        var targets=new HashMap<BackendId,Target>();
        var references=new HashMap<BackendId,Map<Long,CredentialRef>>();
        for(var entry:pins.entrySet()){
            var key=entry.getKey();var pin=entry.getValue();
            if(!key.backend().equals(pin.location().backend())||!key.location().equals(pin.location().id()))
                throw new IllegalArgumentException("invalid staging locations");
            var priorLocation=locations.putIfAbsent(key.location(),pin.location());
            var priorTarget=targets.putIfAbsent(key.backend(),pin.target());
            var priorReference=references.computeIfAbsent(key.backend(),ignored->new HashMap<>())
                    .putIfAbsent(key.revision(),pin.credential());
            if((priorLocation!=null&&!priorLocation.equals(pin.location()))
                    ||(priorTarget!=null&&!priorTarget.equals(pin.target()))
                    ||(priorReference!=null&&!priorReference.equals(pin.credential())))
                throw new IllegalArgumentException("invalid staging locations");
        }
    }
    @Override public String toString(){return "StagingRuntimeDependencies[redacted]";}
}
