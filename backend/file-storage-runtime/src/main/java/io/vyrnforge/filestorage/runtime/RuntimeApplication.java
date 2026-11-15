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
        app.setDefaultProperties(Map.of("server.address", "127.0.0.1", "fs.role", "API",
                "server.error.include-message", "never", "server.error.include-stacktrace", "never",
                "spring.jackson.deserialization.fail-on-unknown-properties", "true",
                "spring.flyway.enabled", "false"));
        app.addInitializers(context -> {
            var env = context.getEnvironment();
            if (!"127.0.0.1".equals(env.getProperty("server.address")))
                throw new IllegalStateException("isolated runtime requires loopback");
            RuntimeRole.parse(env.getProperty("fs.role"));
        });
        return app;
    }
}
