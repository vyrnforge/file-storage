package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.Scope;
import io.vyrnforge.filestorage.persistence.JdbcPolicyControl;
import io.vyrnforge.filestorage.security.SecuredPolicyAdmin;
import io.vyrnforge.filestorage.security.SecuredPolicyAdmin.Action;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Policy-only administration. Both administrative action and location authority are mandatory. */
@RestController
@RequestMapping("/v1/admin/policies")
public final class PolicyAdminRoutes {
    private final SecuredPolicyAdmin security;
    private final JdbcPolicyControl policies;

    public PolicyAdminRoutes(SecuredPolicyAdmin security, JdbcPolicyControl policies) {
        this.security = Objects.requireNonNull(security);
        this.policies = Objects.requireNonNull(policies);
    }

    @GetMapping(value = "/active", produces = "application/json")
    public ResponseEntity<PolicyDtos.Policy> active(HttpServletRequest request) {
        var context = context(request, Action.POLICY_READ);
        var result = policies.active(context.actor(), context.scope()).orElseThrow(() -> new Failure(Code.NOT_FOUND));
        return ApiResponses.success(context.correlation(), PolicyDtos.view(result));
    }

    @GetMapping(value = "/revisions/{revision}", produces = "application/json")
    public ResponseEntity<PolicyDtos.Policy> revision(HttpServletRequest request, @PathVariable("revision") String revision) {
        var context = context(request, Action.POLICY_READ);
        var result = policies.revision(context.actor(), context.scope(), ApiRequest.number(revision, 1, Long.MAX_VALUE))
                .orElseThrow(() -> new Failure(Code.NOT_FOUND));
        return ApiResponses.success(context.correlation(), PolicyDtos.view(result));
    }

    @GetMapping(value = "/revisions", produces = "application/json")
    public ResponseEntity<PolicyDtos.History> history(HttpServletRequest request) {
        var context = context(request, Action.POLICY_READ);
        long before = ApiRequest.number(ApiRequest.query(request, "before", "0"), 0, Long.MAX_VALUE);
        int limit = (int) ApiRequest.number(ApiRequest.query(request, "limit", "50"), 1, 100);
        return ApiResponses.success(context.correlation(),
                new PolicyDtos.History(policies.history(context.actor(), context.scope(), before, limit)));
    }

    @PostMapping(value = "/revisions", consumes = "application/json", produces = "application/json")
    public ResponseEntity<PolicyDtos.Revision> publish(HttpServletRequest request, @RequestBody PolicyDtos.Publish body) {
        var context = context(request, Action.POLICY_PUBLISH);
        var rules = PolicyDtos.rules(body);
        long revision = policies.publish(context.actor(), context.correlation(), context.scope(), body.expectedRevision(), rules);
        return ApiResponses.success(context.correlation(), new PolicyDtos.Revision(revision));
    }

    @PostMapping(value = "/rollback", consumes = "application/json", produces = "application/json")
    public ResponseEntity<PolicyDtos.Revision> rollback(HttpServletRequest request, @RequestBody PolicyDtos.Rollback body) {
        var context = context(request, Action.POLICY_ROLLBACK);
        if (body == null || body.expectedRevision() == null || body.historicalRevision() == null)
            throw new Failure(Code.INVALID);
        long revision = policies.rollback(context.actor(), context.correlation(), context.scope(),
                body.expectedRevision(), body.historicalRevision());
        return ApiResponses.success(context.correlation(), new PolicyDtos.Revision(revision));
    }

    private record Context(Scope scope, SecuredPolicyAdmin.Authorized authorized) {
        io.vyrnforge.platform.kernel.context.ActorReference actor() { return authorized.actor(); }
        io.vyrnforge.platform.kernel.context.CorrelationContext correlation() { return authorized.correlation(); }
    }

    private Context context(HttpServletRequest request, Action action) {
        var input = ApiRequest.from(request);
        var scope = new Scope(input.tenant(), ApiRequest.bounded(ApiRequest.query(request, "application", null), 128),
                ApiRequest.bounded(ApiRequest.query(request, "purpose", null), 128));
        return new Context(scope, security.authorize(input.bearer(), scope, input.correlationId(), action));
    }

}
