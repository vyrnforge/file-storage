package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.LocationId;
import io.vyrnforge.filestorage.persistence.JdbcConfiguration.PolicyRule;
import io.vyrnforge.filestorage.persistence.JdbcPolicyControl.PolicyView;
import java.util.List;
import java.util.UUID;

/** Versioned policy contracts expose only logical location identities. */
public final class PolicyDtos {
    private PolicyDtos() {}

    public record Rule(UUID locationId, Integer priority, Boolean eligible) {}
    public record Publish(Long expectedRevision, List<Rule> rules) {}
    public record Rollback(Long expectedRevision, Long historicalRevision) {}
    public record Revision(long revision) {}
    public record History(List<Long> revisions) {}
    public record Policy(UUID tenantId, String application, String purpose, long revision, List<Rule> rules) {}

    static List<PolicyRule> rules(Publish command) {
        if (command == null || command.expectedRevision() == null || command.rules() == null
                || command.rules().isEmpty() || command.rules().size() > 100) throw new Failure(Code.INVALID);
        return command.rules().stream().map(rule -> {
            if (rule == null || rule.locationId() == null || rule.priority() == null
                    || rule.priority() < 0 || rule.eligible() == null) throw new Failure(Code.INVALID);
            return new PolicyRule(new LocationId(rule.locationId()), rule.priority(), rule.eligible());
        }).toList();
    }

    static Policy view(PolicyView view) {
        return new Policy(view.scope().tenant().value(), view.scope().consumer(), view.scope().purpose(),
                view.revision(), view.rules().stream().map(rule ->
                        new Rule(rule.location().value(), rule.priority(), rule.eligible())).toList());
    }
}
