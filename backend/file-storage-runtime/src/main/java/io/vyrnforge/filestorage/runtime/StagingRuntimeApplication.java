package io.vyrnforge.filestorage.runtime;

import java.util.Objects;
import org.springframework.context.ConfigurableApplicationContext;

/** Entry for reviewed deployment code supplying explicit trusted API/staging beans.
 * No environment-only bootstrap, implicit credentials, default scanner or deployment.
 */
public final class StagingRuntimeApplication {
    private StagingRuntimeApplication(){}
    public static ConfigurableApplicationContext start(Class<?> trustedConfiguration,String... args){
        Objects.requireNonNull(trustedConfiguration);
        return RuntimeApplication.application(StagingRuntimeConfiguration.class,trustedConfiguration).run(args);
    }
}
