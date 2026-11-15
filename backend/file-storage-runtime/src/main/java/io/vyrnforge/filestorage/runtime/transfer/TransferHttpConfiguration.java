package io.vyrnforge.filestorage.runtime.transfer;

import io.vyrnforge.filestorage.runtime.RuntimeRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Opt-in data-plane routes, never imported by default API or runtime executable. */
@Configuration(proxyBeanMethods = false)
public class TransferHttpConfiguration {
    @Bean
    TransferRoutes transferRoutes(PrivateTransferGateway gateway, @Value("${fs.role}") String role) {
        var runtimeRole = RuntimeRole.parse(role);
        if (runtimeRole != RuntimeRole.TRANSFER && runtimeRole != RuntimeRole.COMBINED)
            throw new IllegalStateException("transfer routes require TRANSFER or COMBINED role");
        return new TransferRoutes(gateway);
    }
}
