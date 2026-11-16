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
        Map<LocationId,Pin> locations,CredentialResolver<AutoCloseable> credentials,
        Map<Provider,RegistryTransferProviderResolver.AdapterFactory<AutoCloseable>> factories,
        ContentScanner scanner,EventTransport transport,ActorReference worker,
        RuntimeReadiness.AuthorityProbe readiness,TransferLimits limits,Duration handleTtl,
        Duration operationLease,Duration retryDelay,int attempts,int outboxBatch){
    public record Pin(Target target,Location location,CredentialRef credential){
        public Pin{Objects.requireNonNull(target);Objects.requireNonNull(location);Objects.requireNonNull(credential);
            if(!"https".equals(target.endpoint().getScheme())||location.prefix().isBlank())throw new IllegalArgumentException("invalid staging pin");}
        @Override public String toString(){return "Pin[redacted]";}
    }
    public StagingRuntimeDependencies{
        if(!"staging".equals(environment))throw new IllegalArgumentException("staging environment required");
        Objects.requireNonNull(trust);Objects.requireNonNull(credentials);Objects.requireNonNull(scanner);
        Objects.requireNonNull(transport);Objects.requireNonNull(worker);Objects.requireNonNull(readiness);
        Objects.requireNonNull(limits);Objects.requireNonNull(handleTtl);Objects.requireNonNull(operationLease);Objects.requireNonNull(retryDelay);
        locations=Map.copyOf(locations);factories=Map.copyOf(factories);
        if(locations.isEmpty())throw new IllegalArgumentException("invalid staging locations");
        for(var e:locations.entrySet())if(!e.getKey().equals(e.getValue().location().id())||!factories.containsKey(e.getValue().target().provider()))
            throw new IllegalArgumentException("invalid staging locations");
        if(handleTtl.isZero()||handleTtl.isNegative()||handleTtl.compareTo(Duration.ofHours(1))>0
            ||operationLease.compareTo(limits.timeout())<=0||operationLease.compareTo(Duration.ofMinutes(5))>0
            ||retryDelay.isNegative()||retryDelay.compareTo(Duration.ofMinutes(5))>0||attempts<1||attempts>10
            ||outboxBatch<1||outboxBatch>100)throw new IllegalArgumentException("invalid staging bounds");
    }
    @Override public String toString(){return "StagingRuntimeDependencies[redacted]";}
}
