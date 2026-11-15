package io.vyrnforge.filestorage.runtime.http;

import io.vyrnforge.filestorage.runtime.RuntimeRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** One shared metadata transport boundary for consumer and administrative routes. */
@Configuration(proxyBeanMethods = false)
@Import({ApiErrors.class, MetadataJsonConfiguration.class})
public class HttpBoundaryConfiguration {
    @Bean
    ApiAdmissionFilter apiAdmissionFilter(@Value("${fs.role}") String role) {
        if (!RuntimeRole.parse(role).supportsMetadataApi()) {
            throw new IllegalStateException("metadata routes require API or COMBINED role");
        }
        return new ApiAdmissionFilter();
    }
}
