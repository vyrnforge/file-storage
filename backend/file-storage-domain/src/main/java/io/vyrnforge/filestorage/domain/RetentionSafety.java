package io.vyrnforge.filestorage.domain;

import io.vyrnforge.filestorage.domain.StorageModel.FileState;
import java.time.Instant;
import java.util.Objects;

/** Conservative retention assessment only; no result authorizes physical deletion. */
public final class RetentionSafety {
    private RetentionSafety() {}
    public enum Hold { UNKNOWN, HELD, CLEAR }
    public enum Assessment { NOT_DELETED, EVIDENCE_MISSING, EVIDENCE_STALE, HOLD_UNKNOWN, HELD,
        RETENTION_UNRESOLVED, RETENTION_ACTIVE, REVIEW_REQUIRED }

    public record Evidence(long lifecycleRevision,long policyRevision,Hold hold,Instant retainUntil,
                           Instant observedAt,Instant validUntil) {
        public Evidence {
            if(lifecycleRevision<=0||lifecycleRevision%2!=1||policyRevision<=0)
                throw new IllegalArgumentException("invalid evidence revision");
            Objects.requireNonNull(hold);Objects.requireNonNull(observedAt);Objects.requireNonNull(validUntil);
            if(!validUntil.isAfter(observedAt))throw new IllegalArgumentException("invalid evidence interval");
        }
    }

    /** Even REVIEW_REQUIRED is blocked: policy authority, quota, reader quiescence and purge are separate gates. */
    public static Assessment assess(FileState state,long revision,Evidence evidence,Instant now) {
        Objects.requireNonNull(now);
        if(state!=FileState.SOFT_DELETED)return Assessment.NOT_DELETED;
        if(evidence==null)return Assessment.EVIDENCE_MISSING;
        if(evidence.lifecycleRevision()!=revision||now.isBefore(evidence.observedAt())||!now.isBefore(evidence.validUntil()))
            return Assessment.EVIDENCE_STALE;
        if(evidence.hold()==Hold.HELD)return Assessment.HELD;
        if(evidence.hold()!=Hold.CLEAR)return Assessment.HOLD_UNKNOWN;
        if(evidence.retainUntil()==null)return Assessment.RETENTION_UNRESOLVED;
        if(now.isBefore(evidence.retainUntil()))return Assessment.RETENTION_ACTIVE;
        return Assessment.REVIEW_REQUIRED;
    }
}
