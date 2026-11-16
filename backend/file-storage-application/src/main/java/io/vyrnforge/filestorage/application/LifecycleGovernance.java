package io.vyrnforge.filestorage.application;

import io.vyrnforge.filestorage.domain.StorageModel.*;
import io.vyrnforge.platform.kernel.context.ActorReference;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Internal trusted authority contracts, not consumer DTOs or production policy defaults. */
public final class LifecycleGovernance {
    private LifecycleGovernance(){}
    public enum Action { DRAFT_POLICY, REVIEW_POLICY, ASSERT_HOLD, REQUEST_READER_REVIEW, CONFIRM_READER_STOPPED, ACTIVATE_ISOLATED_POLICY, RESOLVE_ISOLATED_POLICY, GENERATE_ISOLATED_EVIDENCE, ACCOUNT_ISOLATED_QUOTA, ENABLE_ISOLATED_QUOTA_INTEGRATION, RECONCILE_ISOLATED_QUOTA, DRAFT_ISOLATED_QUOTA, REVIEW_ISOLATED_QUOTA, CAPTURE_QUOTA_FINDING, REVIEW_QUOTA_FINDING }
    public enum QuotaMode { CHARGE_UNTIL_VERIFIED_PURGE, RELEASE_ON_LOGICAL_DELETE }
    /** Composition must establish actor identity and tenant/file-specific permission. No default implementation. */
    @FunctionalInterface public interface Authority {
        boolean allows(ActorReference actor,TenantId tenant,FileId file,Action action);
    }
    public record PolicyDraft(long expectedRevision,long retentionSeconds,QuotaMode quotaMode){
        public PolicyDraft{
            if(expectedRevision<0||expectedRevision==Long.MAX_VALUE||retentionSeconds<=0)throw new IllegalArgumentException("invalid policy");
            Objects.requireNonNull(quotaMode);
        }
    }
    public record ReaderClaim(TenantId tenant,FileId file,VersionId version,String handleHash,Instant claimedAt,Instant expiresAt){
        @Override public String toString(){return "ReaderClaim[redacted]";}
    }
    /** Verify actual cessation of remote I/O for the exact immutable claim, not expiry or a user assertion.
     * No production implementation is supplied in A5. */
    @FunctionalInterface public interface DrainVerifier {
        boolean confirmedStopped(ReaderClaim claim,String evidenceSha256,ActorReference reviewer);
    }
    public record ReaderReview(UUID requestId,String evidenceSha256){
        public ReaderReview{Objects.requireNonNull(requestId);digest(evidenceSha256);}
    }
    public static void digest(String value){
        if(value==null||!value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid evidence digest");
    }
}
