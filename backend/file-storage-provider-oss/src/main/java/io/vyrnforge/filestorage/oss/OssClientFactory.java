package io.vyrnforge.filestorage.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.SignVersion;
import io.vyrnforge.filestorage.domain.StorageModel.Target;
import io.vyrnforge.filestorage.domain.StorageModel.Provider;
import io.vyrnforge.filestorage.spi.StorageProvider.ErrorCode;
import io.vyrnforge.filestorage.spi.StorageProvider.StorageFailure;
import java.util.Objects;

/**
 * Internal SDK composition helper. The registry must authorize the exact
 * endpoint/DNS/network and credential reference before calling this factory.
 * No default credential chain, redirects, or arbitrary consumer endpoint.
 */
public final class OssClientFactory {
    private OssClientFactory() {}

    public static OSS create(Target target, CredentialsProvider credentials,
                             int connectMillis, int acquireMillis, int socketMillis,
                             int requestMillis, int maxConnections) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(credentials);
        if (target.provider() != Provider.OSS || connectMillis < 1 || acquireMillis < 1
                || socketMillis < 1 || requestMillis < 1 || maxConnections < 1
                || !(target.endpoint().getPath().isEmpty() || "/".equals(target.endpoint().getPath()))) {
            throw new StorageFailure(ErrorCode.INVALID);
        }
        var config = new ClientBuilderConfiguration();
        config.setSignatureVersion(SignVersion.V4);
        config.setConnectionTimeout(connectMillis);
        config.setConnectionRequestTimeout(acquireMillis);
        config.setSocketTimeout(socketMillis);
        config.setRequestTimeoutEnabled(true);
        config.setRequestTimeout(requestMillis);
        config.setMaxConnections(maxConnections);
        config.setMaxErrorRetry(0);
        config.setRedirectEnable(false);
        config.setCrcCheckEnabled(true);
        switch (target.addressing()) {
            case AUTO, VIRTUAL_HOST -> {
                config.setSupportCname(false);
                config.setSLDEnabled(false);
            }
            case PATH -> {
                config.setSupportCname(false);
                config.setSLDEnabled(true);
            }
            case CNAME -> {
                // A bucket-bound CNAME needs explicit binding and real profile
                // evidence. Never silently use the same CNAME for multiple buckets.
                throw new StorageFailure(ErrorCode.UNSUPPORTED);
            }
        }
        try {
            return OSSClientBuilder.create().endpoint(target.endpoint().toString())
                    .region(target.region()).credentialsProvider(credentials)
                    .clientConfiguration(config).build();
        } catch (RuntimeException ex) {
            throw new StorageFailure(ErrorCode.INVALID);
        }
    }
}
