package io.vyrnforge.filestorage.runtime;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/** Isolated health-only executable. Deliberately no component scanning or live route registration. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(HealthRoutes.class)
public class RuntimeApplication {
    public static void main(String[] args) { start(args); }

    static ConfigurableApplicationContext start(String... args) {
        return application().run(args);
    }

    static SpringApplication application(Class<?>... extraTestSources) {
        var app = new SpringApplication(RuntimeApplication.class);
        for (Class<?> source : extraTestSources) app.addPrimarySources(Set.of(source));
        app.setDefaultProperties(Map.ofEntries(
                Map.entry("server.address", "127.0.0.1"), Map.entry("fs.role", "API"),
                Map.entry("server.error.include-message", "never"), Map.entry("server.error.include-stacktrace", "never"),
                Map.entry("spring.jackson.deserialization.fail-on-unknown-properties", "true"),
                Map.entry("spring.jackson.deserialization.fail-on-trailing-tokens", "true"),
                Map.entry("spring.jackson.deserialization.accept-float-as-int", "false"),
                Map.entry("spring.jackson.mapper.allow-coercion-of-scalars", "false"),
                Map.entry("spring.jackson.read.strict-duplicate-detection", "true"),
                Map.entry("spring.jackson.factory.constraints.read.max-nesting-depth", "32"),
                Map.entry("spring.flyway.enabled", "false")));
        app.addInitializers(context -> {
            var env = context.getEnvironment();
            if (!"127.0.0.1".equals(env.getProperty("server.address")))
                throw new IllegalStateException("isolated runtime requires loopback");
            RuntimeRole.parse(env.getProperty("fs.role"));
        });
        return app;
    }
}
