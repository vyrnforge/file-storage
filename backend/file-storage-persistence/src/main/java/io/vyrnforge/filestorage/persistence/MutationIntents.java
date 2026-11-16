package io.vyrnforge.filestorage.persistence;

import io.vyrnforge.filestorage.application.FileApplication.Entry;
import io.vyrnforge.filestorage.domain.StorageModel.Scope;
import io.vyrnforge.platform.kernel.audit.AuditEvent;
import io.vyrnforge.platform.kernel.audit.AuditIntentPort;
import io.vyrnforge.platform.kernel.audit.AuditOutcome;
import io.vyrnforge.platform.kernel.context.ActorReference;
import io.vyrnforge.platform.kernel.context.ActorType;
import io.vyrnforge.platform.kernel.context.CorrelationContext;
import io.vyrnforge.platform.kernel.context.ResourceReference;
import io.vyrnforge.platform.kernel.event.EventEnvelope;
import io.vyrnforge.platform.kernel.event.EventIntentPort;
import io.vyrnforge.platform.kernel.ids.UuidV7;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Domain-owned, minimal intent payloads; only Kernel transaction-bound writers. */
final class MutationIntents {
    private MutationIntents() {
    }

    static void initiated(EventIntentPort events, AuditIntentPort audits, Clock clock,
                          CorrelationContext correlation, Entry entry) {
        var caller = entry.request().caller();
        record(events, audits, clock, correlation,
                new ActorReference(ActorType.USER, caller.actor(), null, null), caller.tenant().value(),
                "file-storage.upload.initiated", "file-storage.file", entry.file().id().value(),
                Map.of("versionId", entry.version().id().value().toString(),
                        "sessionId", entry.session().id().value().toString(),
                        "state", "INITIATED"));
    }

    static String policyId(Scope scope) {
        // Length-prefixed scope components; no delimiter ambiguity, within Kernel 512-char bound.
        return scope.consumer().length() + ":" + scope.consumer() + scope.purpose();
    }

    static void record(EventIntentPort events, AuditIntentPort audits, Clock clock,
                       CorrelationContext correlation, ActorReference actor, UUID tenant,
                       String type, String resourceType, String resourceId, Map<String, Object> payload) {
        record(events,audits,clock,correlation,actor,tenant,type,resourceType,resourceId,payload,
                UuidV7.generate(),UuidV7.generate());
    }

    /** Explicit IDs bind an audited command receipt to the exact Kernel intents. */
    static void record(EventIntentPort events, AuditIntentPort audits, Clock clock,
                       CorrelationContext correlation, ActorReference actor, UUID tenant,
                       String type, String resourceType, String resourceId, Map<String, Object> payload,
                       UUID auditId, UUID eventId) {
        Objects.requireNonNull(correlation);
        // Do not persist raw identity issuer/subject or display name in business intents.
        var safeActor = new ActorReference(actor.actorType(), actor.actorId(), null, null);
        var now = clock.instant();
        var changes = new HashMap<String, Object>(payload);
        changes.put("requestId", correlation.requestId().toString());
        audits.accept(new AuditEvent(auditId, now, tenant, safeActor, type,
                new ResourceReference(resourceType, resourceId, tenant), AuditOutcome.SUCCESS,
                "file-storage", correlation.correlationId(), null, changes));
        events.accept(new EventEnvelope<>(eventId, type, 1, now, tenant, "file-storage",
                resourceType, resourceId, correlation.correlationId(), correlation.causationId(),
                safeActor, Map.copyOf(payload)));
    }
}
