package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.application.FileApplication.Code;
import io.vyrnforge.filestorage.application.FileApplication.Failure;
import io.vyrnforge.filestorage.domain.StorageModel.BackendId;
import io.vyrnforge.filestorage.runtime.BackendAdministration;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin;
import io.vyrnforge.filestorage.security.SecuredBackendAdmin.Action;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Platform-level backend administration; no tenant or provider data in responses. */
@RestController
@RequestMapping("/v1/admin/backends")
public final class BackendAdminRoutes {
    private final SecuredBackendAdmin security;
    private final BackendAdministration admin;

    public BackendAdminRoutes(SecuredBackendAdmin security, BackendAdministration admin) {
        this.security = Objects.requireNonNull(security);
        this.admin = Objects.requireNonNull(admin);
    }

    @GetMapping(produces = "application/json")
    public ResponseEntity<List<BackendDtos.Backend>> list(HttpServletRequest request) {
        var context = authorize(request, Action.LIST, null);
        return ApiResponses.success(context.correlation(), admin.registry().backends(after(request), limit(request))
                .stream().map(BackendDtos::backend).toList());
    }

    @PostMapping(consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Registered> register(HttpServletRequest request, @RequestBody BackendDtos.Register body) {
        var context = authorize(request, Action.REGISTER, null);
        var backend = admin.register(context, BackendDtos.target(body), BackendDtos.reference(body.credentialRef()));
        return ApiResponses.success(context.correlation(), new BackendDtos.Registered(backend.value(), 1));
    }

    @GetMapping(value = "/{backendId}/active", produces = "application/json")
    public ResponseEntity<BackendDtos.Revision> active(HttpServletRequest request, @PathVariable("backendId") String id) {
        var backend = backend(id);
        var context = authorize(request, Action.READ, backend);
        var view = admin.registry().active(backend).orElseThrow(() -> new Failure(Code.NOT_FOUND));
        return ApiResponses.success(context.correlation(), BackendDtos.revision(view));
    }

    @GetMapping(value = "/{backendId}/revisions", produces = "application/json")
    public ResponseEntity<List<BackendDtos.Revision>> history(HttpServletRequest request, @PathVariable("backendId") String id) {
        var backend = backend(id);
        var context = authorize(request, Action.READ, backend);
        long before = number(single(request, "before", "0"), 0, Long.MAX_VALUE);
        return ApiResponses.success(context.correlation(), admin.registry().history(backend, before, limit(request))
                .stream().map(BackendDtos::revision).toList());
    }

    @GetMapping(value = "/{backendId}/locations", produces = "application/json")
    public ResponseEntity<List<BackendDtos.Location>> locations(HttpServletRequest request, @PathVariable("backendId") String id) {
        var backend = backend(id);
        var context = authorize(request, Action.READ, backend);
        return ApiResponses.success(context.correlation(), admin.registry().locations(backend, after(request), limit(request))
                .stream().map(BackendDtos::location).toList());
    }

    @PostMapping(value = "/{backendId}/locations", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Location> location(HttpServletRequest request, @PathVariable("backendId") String id,
            @RequestBody BackendDtos.LocationInput body) {
        var backend = backend(id);
        var context = authorize(request, Action.REGISTER_LOCATION, backend);
        if (body == null || body.prefix() == null || body.prefix().length() > 512) throw new Failure(Code.INVALID);
        var location = admin.registry().registerLocation(context.actor(), context.correlation(), backend,
                ApiRequest.bounded(body.bucket(), 63), body.prefix());
        return ApiResponses.success(context.correlation(), new BackendDtos.Location(location.value(), backend.value()));
    }

    @PostMapping(value = "/{backendId}/credentials", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Changed> credential(HttpServletRequest request, @PathVariable("backendId") String id,
            @RequestBody BackendDtos.CredentialInput body) {
        var backend = backend(id);
        var context = authorize(request, Action.REVISE_CREDENTIAL, backend);
        if (body == null) throw new Failure(Code.INVALID);
        long revision = admin.registry().reviseCredential(context.actor(), context.correlation(), backend,
                BackendDtos.revision(body.expectedRevision(), 1), BackendDtos.reference(body.credentialRef()));
        return ApiResponses.success(context.correlation(), new BackendDtos.Changed(revision));
    }

    @PostMapping(value = "/{backendId}/validate", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Changed> validate(HttpServletRequest request, @PathVariable("backendId") String id,
            @RequestBody BackendDtos.RevisionInput body) {
        var backend = backend(id);
        var context = authorize(request, Action.VALIDATE, backend);
        return ApiResponses.success(context.correlation(), new BackendDtos.Changed(admin.validate(context, backend, revision(body))));
    }

    @PostMapping(value = "/{backendId}/activate", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Changed> activate(HttpServletRequest request, @PathVariable("backendId") String id,
            @RequestBody BackendDtos.ActivateInput body) {
        var backend = backend(id);
        var context = authorize(request, Action.ACTIVATE, backend);
        if (body == null) throw new Failure(Code.INVALID);
        return ApiResponses.success(context.correlation(), new BackendDtos.Changed(admin.activate(context, backend,
                BackendDtos.revision(body.expectedRevision(), 0), BackendDtos.revision(body.candidateRevision(), 1))));
    }

    @PostMapping(value = "/{backendId}/drain", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Changed> drain(HttpServletRequest request, @PathVariable("backendId") String id,
            @RequestBody BackendDtos.RevisionInput body) {
        var backend = backend(id);
        var context = authorize(request, Action.DRAIN, backend);
        return ApiResponses.success(context.correlation(), new BackendDtos.Changed(admin.drain(context, backend, revision(body))));
    }

    @PostMapping(value = "/{backendId}/retire", consumes = "application/json", produces = "application/json")
    public ResponseEntity<BackendDtos.Changed> retire(HttpServletRequest request, @PathVariable("backendId") String id,
            @RequestBody BackendDtos.RevisionInput body) {
        var backend = backend(id);
        var context = authorize(request, Action.RETIRE, backend);
        return ApiResponses.success(context.correlation(), new BackendDtos.Changed(admin.retire(context, backend, revision(body))));
    }

    private SecuredBackendAdmin.Authorized authorize(HttpServletRequest request, Action action, BackendId backend) {
        if (request.getHeader("X-Tenant-Id") != null) throw new Failure(Code.INVALID);
        return security.authorize(ApiRequest.bearer(request), ApiRequest.correlation(request), action, backend);
    }

    private static BackendId backend(String id) { return new BackendId(ApiRequest.uuid(id)); }
    private static long revision(BackendDtos.RevisionInput body) {
        if (body == null) throw new Failure(Code.INVALID);
        return BackendDtos.revision(body.revision(), 1);
    }
    private static UUID after(HttpServletRequest request) {
        String value = single(request, "after", null);
        return value == null ? null : ApiRequest.uuid(value);
    }
    private static int limit(HttpServletRequest request) {
        return (int) number(single(request, "limit", "50"), 1, 100);
    }
    private static String single(HttpServletRequest request, String name, String fallback) {
        var values = request.getParameterValues(name);
        if (values == null) return fallback;
        if (values.length != 1) throw new Failure(Code.INVALID);
        return values[0];
    }
    private static long number(String value, long min, long max) {
        try {
            if (value == null || !value.matches("[0-9]{1,19}")) throw new Failure(Code.INVALID);
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw new Failure(Code.INVALID);
            return parsed;
        } catch (NumberFormatException ex) { throw new Failure(Code.INVALID); }
    }
}
