package io.vyrnforge.filestorage.runtime;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness independent of dependencies; only explicitly composed API can be ready. */
@RestController
public final class HealthRoutes {
    private final RuntimeRole role;
    private final ObjectProvider<RuntimeReadiness> readiness;

    private final ObjectProvider<StagingOperations> staging;

    public HealthRoutes(@Value("${fs.role}") String role, ObjectProvider<RuntimeReadiness> readiness, ObjectProvider<StagingOperations> staging) {
        this.role = RuntimeRole.parse(role);
        this.readiness = readiness;
        this.staging=staging;
    }

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, String>> live() {
        return health(true);
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        // COMBINED is not ready until transfer and worker responsibilities exist.
        boolean ready = false;
        if (role == RuntimeRole.API) {
            try {
                var probe = readiness.getIfAvailable();
                ready = probe != null && probe.ready();
            } catch (RuntimeException ignored) { /* fail closed */ }
        }
        if(role==RuntimeRole.COMBINED){
            try{var ops=staging.getIfAvailable();ready=ops!=null&&ops.ready();}catch(RuntimeException ignored){/* fail closed */}
        }
        return health(ready);
    }

    private ResponseEntity<Map<String, String>> health(boolean up) {
        return ResponseEntity.status(up ? 200 : 503).header("Cache-Control", "no-store")
                .body(Map.of("status", up ? "UP" : "DOWN", "role", role.name()));
    }
}
