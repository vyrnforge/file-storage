package io.vyrnforge.filestorage.runtime;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** No dependency details or provider configuration; readiness stays closed in this slice. */
@RestController
public final class HealthRoutes {
    private final String role;
    public HealthRoutes(@Value("${fs.role}") String role) { this.role = role; }

    @GetMapping("/health/live")
    public Map<String, String> live() { return Map.of("status", "UP", "role", role); }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        return ResponseEntity.status(503).body(Map.of("status", "DOWN", "role", role));
    }
}
